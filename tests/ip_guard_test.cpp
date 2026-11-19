#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "ip_guard.hpp"
#include <arpa/inet.h>
#include <cassert>
#include <cerrno>
#include <cstring>
#include <algorithm>
#include <cstdio>
#include <fcntl.h>
#include <vector>
#include <sys/uio.h>
#include <sys/sendfile.h>
#include <dlfcn.h>
#include <iostream>
#include <map>
#include <netdb.h>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <chrono>
#include <unistd.h>
#include <sys/resource.h>
#include <sys/wait.h>
using namespace fkclaude::ip_guard;
namespace {
std::map<std::string, void*> wrappers;
std::map<void*, std::vector<std::string>> names;
std::map<void*, unsigned> hook_calls;
std::string missing, failure;
void* resolver(const char* name) {
    if (missing == name) return nullptr;
    void* p = dlsym(RTLD_DEFAULT, name); assert(p);
    auto& aliases = names[p];
    if (std::find(aliases.begin(), aliases.end(), name) == aliases.end()) aliases.emplace_back(name);
    return p;
}
int hook(void* target, void* replacement, void** original) {
    for (const auto& name : names.at(target)) if (name == failure) return -1;
    *original = target;
    for (const auto& name : names.at(target)) wrappers[name] = replacement;
    ++hook_calls[target];
    // Hook engines may invoke an already patched libc entry while installing another.
    if (wrappers.count("getaddrinfo")) {
        using Resolver = int (*)(const char*,const char*,const addrinfo*,addrinfo**);
        addrinfo* result = nullptr;
        assert(reinterpret_cast<Resolver>(wrappers.at("getaddrinfo"))("localhost",nullptr,nullptr,&result)==EAI_AGAIN);
    }
    return 0;
}
template<class T> T wrap(const char* n) { return reinterpret_cast<T>(wrappers.at(n)); }
using C = int (*)(int,const sockaddr*,socklen_t);
using S = ssize_t (*)(int,const void*,size_t,int);
using ST = ssize_t (*)(int,const void*,size_t,int,const sockaddr*,socklen_t);
using SM = ssize_t (*)(int,const msghdr*,int);
using SMM = int (*)(int,mmsghdr*,unsigned,int);
using G = int (*)(const char*,const char*,const addrinfo*,addrinfo**);
using W = ssize_t (*)(int,const void*,size_t);
using WV = ssize_t (*)(int,const iovec*,int);
using SF = ssize_t (*)(int,int,off_t*,size_t);
using SF64 = ssize_t (*)(int,int,off64_t*,size_t);
using SP = ssize_t (*)(int,off64_t*,int,off64_t*,size_t,unsigned);
sockaddr_in address4() { sockaddr_in a{}; a.sin_family=AF_INET; a.sin_addr.s_addr=htonl(INADDR_LOOPBACK); a.sin_port=htons(9); return a; }
struct Pair {
    int client, peer;
    Pair(bool exempt = false) {
        int listener = socket(AF_INET, SOCK_STREAM, 0); assert(listener>=0);
        auto a=address4(); a.sin_port=0;
        assert(bind(listener,reinterpret_cast<sockaddr*>(&a),sizeof(a))==0);
        assert(listen(listener,1)==0); socklen_t n=sizeof(a);
        assert(getsockname(listener,reinterpret_cast<sockaddr*>(&a),&n)==0);
        client=socket(AF_INET,SOCK_STREAM,0); assert(client>=0);
        assert(wrap<C>("connect")(client,reinterpret_cast<sockaddr*>(&a),n)==0);
        peer=accept(listener,nullptr,nullptr); assert(peer>=0); close(listener);
        // Register the in-process server endpoint too. Real probe servers are
        // outside this process; only our fake-hook host fixture needs this.
        if(exempt) wrap<C>("connect")(peer,reinterpret_cast<sockaddr*>(&a),n);
    }
    ~Pair(){close(client);close(peer);}
};
void denied(int fd) {
    errno=0; assert(wrap<S>("send")(fd,"x",1,MSG_NOSIGNAL)==-1 && errno==EACCES);
}
void outputs_denied(int fd) {
    iovec vectors[]{ {const_cast<char*>("a"),1}, {const_cast<char*>("b"),1} };
    assert(wrap<W>("write")(fd,"x",1)==-1 && errno==EACCES);
    assert(wrap<WV>("writev")(fd,vectors,2)==-1 && errno==EACCES);
    off_t offset=0; off64_t offset64=0;
    assert(wrap<SF>("sendfile")(fd,-1,&offset,1)==-1 && errno==EACCES);
    assert(wrap<SF64>("sendfile64")(fd,-1,&offset64,1)==-1 && errno==EACCES);
    assert(wrap<SP>("splice")(-1,nullptr,fd,nullptr,1,0)==-1 && errno==EACCES);
}
void received(int fd, const char* expected, size_t count) {
    char buffer[8]{};
    assert(read(fd,buffer,count)==static_cast<ssize_t>(count));
    assert(std::memcmp(buffer,expected,count)==0);
}
void outputs_allowed(int fd, int reader) {
    iovec vectors[]{ {const_cast<char*>("a"),1}, {const_cast<char*>("b"),1} };
    assert(wrap<W>("write")(fd,"x",1)==1); received(reader,"x",1);
    assert(wrap<WV>("writev")(fd,vectors,2)==2); received(reader,"ab",2);
    FILE* source=std::tmpfile(); assert(source);
    assert(write(fileno(source),"x",1)==1);
    off_t offset=0;
    assert(wrap<SF>("sendfile")(fd,fileno(source),&offset,1)==1); received(reader,"x",1);
    off64_t offset64=0;
    assert(wrap<SF64>("sendfile64")(fd,fileno(source),&offset64,1)==1); received(reader,"x",1);
    std::fclose(source);
    int pipefd[2]; assert(pipe(pipefd)==0); assert(write(pipefd[1],"x",1)==1);
    assert(wrap<SP>("splice")(pipefd[0],nullptr,fd,nullptr,1,0)==1); received(reader,"x",1);
    close(pipefd[0]);close(pipefd[1]);
}
struct DatagramPair {
    int client, peer;
    explicit DatagramPair(int family, bool mapped=false) {
        int peer_family=mapped ? AF_INET : family;
        peer=socket(peer_family,SOCK_DGRAM,0);assert(peer>=0);
        sockaddr_storage storage{};
        socklen_t length;
        if(peer_family==AF_INET) {
            auto& a=*reinterpret_cast<sockaddr_in*>(&storage);
            a=address4();a.sin_port=0;length=sizeof(a);
        } else {
            auto& a=*reinterpret_cast<sockaddr_in6*>(&storage);
            a.sin6_family=AF_INET6;a.sin6_addr=in6addr_loopback;length=sizeof(a);
        }
        assert(bind(peer,reinterpret_cast<sockaddr*>(&storage),length)==0);
        assert(getsockname(peer,reinterpret_cast<sockaddr*>(&storage),&length)==0);
        client=socket(family,SOCK_DGRAM,0);assert(client>=0);
        if(mapped) {
            auto port=reinterpret_cast<sockaddr_in*>(&storage)->sin_port;
            storage={};
            auto& a=*reinterpret_cast<sockaddr_in6*>(&storage);
            a.sin6_family=AF_INET6;a.sin6_port=port;
            assert(inet_pton(AF_INET6,"::ffff:127.0.0.1",&a.sin6_addr)==1);
            length=sizeof(a);
            int disabled=0;assert(setsockopt(client,IPPROTO_IPV6,IPV6_V6ONLY,&disabled,sizeof(disabled))==0);
        }
        // The host fixture intentionally uses libc directly; only its tested
        // writes use captured gates. This leaves real live sockets while denied.
        assert(connect(client,reinterpret_cast<sockaddr*>(&storage),length)==0);
    }
    ~DatagramPair(){close(client);close(peer);}
    void allowed() {
        assert(wrap<W>("write")(client,"x",1)==1);received(peer,"x",1);
        iovec vectors[]{ {const_cast<char*>("a"),1}, {const_cast<char*>("b"),1} };
        assert(wrap<WV>("writev")(client,vectors,2)==2);received(peer,"ab",2);
    }
};
void test_generic_families() {
    deny_without_revoke_for_test();
    DatagramPair v4(AF_INET),v6(AF_INET6),mapped(AF_INET6,true);
    permit(4,10000);v4.allowed();mapped.allowed();outputs_denied(v6.client);
    deny_without_revoke_for_test();
    permit(6,10000);v6.allowed();outputs_denied(v4.client);outputs_denied(mapped.client);
}
void test_generic_writes() {
    // A live TCP connection with a closed gate but no shutdown proves rejection
    // comes from each wrapper, not EPIPE from an already dead fixture.
    permit(4,10000);
    {
        Pair p;
        outputs_allowed(p.client,p.peer);
        deny_without_revoke_for_test();
        assert(send(p.client,"c",1,MSG_NOSIGNAL)==1); received(p.peer,"c",1);
        outputs_denied(p.client);
        char b; assert(recv(p.peer,&b,1,MSG_DONTWAIT)==-1 && (errno==EAGAIN || errno==EWOULDBLOCK));
    }
    // Local IPC and regular files retain their normal I/O behavior while blocked.
    block();
    int unixfd[2]; assert(socketpair(AF_UNIX,SOCK_STREAM,0,unixfd)==0);
    outputs_allowed(unixfd[0],unixfd[1]);close(unixfd[0]);close(unixfd[1]);
    int pipefd[2];assert(pipe(pipefd)==0);
    outputs_allowed(pipefd[1],pipefd[0]);close(pipefd[0]);close(pipefd[1]);
    FILE* file=std::tmpfile();assert(file);
    iovec vectors[]{ {const_cast<char*>("a"),1}, {const_cast<char*>("b"),1} };
    assert(wrap<W>("write")(fileno(file),"x",1)==1);
    assert(wrap<WV>("writev")(fileno(file),vectors,2)==2);
    assert(lseek(fileno(file),0,SEEK_SET)==0);received(fileno(file),"xab",3);
    std::fclose(file);
    assert(wrap<W>("write")(-1,"x",1)==-1 && errno==EBADF);
    assert(wrap<WV>("writev")(-1,vectors,2)==-1 && errno==EBADF);
    // Registration is per thread and descriptor identity, not a process-wide pass.
    probe(true);
    {
        Pair p(true);
        block();
        outputs_allowed(p.client,p.peer);
        std::thread outsider([&]{outputs_denied(p.client);});outsider.join();
        int alias=dup(p.client);assert(alias>=0);outputs_denied(alias);denied(alias);close(alias);
        int old=p.client;close(p.client);p.client=-1;
        permit(4,10000);
        std::thread replace([&]{
            Pair q;
            assert(dup2(q.client,old)==old);
            // q's peer may be old if the kernel reused it. Do not let its RAII
            // cleanup close the copied client descriptor until main checks it.
            if(q.peer==old)q.peer=-1;
            if(q.client==old)q.client=-1;
        });
        replace.join();
        deny_without_revoke_for_test();outputs_denied(old);denied(old);close(old);
        probe(false);
        outputs_denied(p.peer);
    }
    std::cout<<"generic write gates passed (write/writev/sendfile/sendfile64/splice)\n";
}
}
int main() {
    std::cout << "HOST FAKE INLINE HOOK: captures wrappers; does not patch libc\n";
    set_hook_function(hook); set_resolver_for_test(resolver);
    // Missing installer cannot claim enforcement.
    set_hook_function(nullptr); assert(!start()); set_hook_function(hook);
    // Revocation cannot silently succeed when descriptor inspection is impossible.
    pid_t child = fork(); assert(child >= 0);
    if (child == 0) {
        rlimit limit{3,3}; if (setrlimit(RLIMIT_NOFILE, &limit) != 0) _exit(99);
        block(); _exit(98);
    }
    int status; assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 137);
    for (const auto* symbol : {"sendmmsg","write","writev","sendfile","sendfile64","splice"}) {
        missing=symbol; assert(!start()); assert(wrappers.empty());
    }
    missing.clear(); failure="sendmsg"; assert(!start());
    int fd=socket(AF_INET,SOCK_DGRAM,0); assert(fd>=0); denied(fd);
    failure.clear(); assert(start()); assert(start());
    close(fd); fd=socket(AF_INET,SOCK_DGRAM,0); assert(fd>=0);
    assert(permit_checked(4, 10000, 0) == 0); denied(fd);
    auto a=address4();
    denied(fd);
    assert(wrap<ST>("sendto")(fd,"x",1,0,reinterpret_cast<sockaddr*>(&a),sizeof(a))==-1 && errno==EACCES);
    iovec io{const_cast<char*>("x"),1}; msghdr msg{};
    msg.msg_name=&a; msg.msg_namelen=sizeof(a); msg.msg_iov=&io; msg.msg_iovlen=1;
    assert(wrap<SM>("sendmsg")(fd,&msg,0)==-1 && errno==EACCES);
    mmsghdr multi{}; multi.msg_hdr=msg;
    assert(wrap<SMM>("sendmmsg")(fd,&multi,1,0)==-1 && errno==EACCES);
    addrinfo* out=nullptr; addrinfo hints{}; hints.ai_family=AF_INET; hints.ai_flags=AI_NUMERICHOST;
    assert(wrap<G>("getaddrinfo")("127.0.0.1",nullptr,&hints,&out)==EAI_AGAIN);
    // An expired observation revoked this socket; a later grant cannot revive it.
    close(fd); fd=socket(AF_INET,SOCK_DGRAM,0); assert(fd>=0);
    permit(4,10000);
    assert(wrap<ST>("sendto")(fd,"x",1,0,reinterpret_cast<sockaddr*>(&a),sizeof(a))==1);
    assert(wrap<SM>("sendmsg")(fd,&msg,0)==1);
    assert(wrap<SMM>("sendmmsg")(fd,&multi,1,0)==1);
    assert(wrap<G>("getaddrinfo")("127.0.0.1",nullptr,&hints,&out)==0); freeaddrinfo(out);
    // Bad descriptors preserve ordinary errors, rather than becoming EACCES.
    assert(wrap<S>("send")(-1,"x",1,0)==-1 && errno==EBADF);
    int v6=socket(AF_INET6,SOCK_DGRAM,0); assert(v6>=0);
    sockaddr_in6 a6{}; a6.sin6_family=AF_INET6; a6.sin6_port=htons(9); a6.sin6_addr=in6addr_loopback;
    auto send6=[&]{return wrap<ST>("sendto")(v6,"x",1,0,reinterpret_cast<sockaddr*>(&a6),sizeof(a6));};
    assert(send6()==-1 && errno==EACCES); permit(6,10000); assert(send6()==1);
    block(); permit(4,10000);
    close(v6); v6=socket(AF_INET6,SOCK_DGRAM,0); assert(v6>=0);
    assert(inet_pton(AF_INET6,"::ffff:127.0.0.1",&a6.sin6_addr)==1); assert(send6()==1);
    multi.msg_hdr.msg_name=&a6; multi.msg_hdr.msg_namelen=sizeof(a6);
    assert(wrap<SMM>("sendmmsg")(v6,&multi,1,0)==1);
    block(); permit(6,10000); assert(send6()==-1 && errno==EACCES);
    int unixfd[2]; assert(socketpair(AF_UNIX,SOCK_STREAM,0,unixfd)==0);
    block(); assert(wrap<S>("send")(unixfd[0],"x",1,0)==1); close(unixfd[0]); close(unixfd[1]);
    permit(4,10000);
    { Pair p; block(); char b; assert(recv(p.peer,&b,1,MSG_DONTWAIT)==0); denied(p.client); }
    permit(4,120);
    { Pair p; std::this_thread::sleep_for(std::chrono::milliseconds(350)); char b; assert(recv(p.peer,&b,1,MSG_DONTWAIT)==0); denied(p.client); }
    probe(true); probe(true);
    { Pair p(true); block();
      std::thread outsider([&]{denied(p.client);}); outsider.join();
      assert(wrap<S>("send")(p.client,"x",1,MSG_NOSIGNAL)==1);
      probe(false); block(); assert(wrap<S>("send")(p.client,"x",1,MSG_NOSIGNAL)==1);
      // Reuse the exact numeric descriptor while its old probe identity remains.
      int old=p.client; close(p.client); p.client=-1;
      permit(4,10000);
      std::thread other([&]{ Pair q; assert(dup2(q.client,old)==old); block(); denied(old); close(old); }); other.join();
      probe(false); block(); denied(p.peer);
    }
    probe(false); // Unbalanced exit is harmless.
    probe(true); out=nullptr;
    assert(wrap<G>("getaddrinfo")("127.0.0.1",nullptr,&hints,&out)==0); freeaddrinfo(out); probe(false);
    permit(4,-1); denied(fd); permit(99,10000); denied(fd);
    close(fd);close(v6);
    test_generic_writes();
    test_generic_families();
    assert(hook_calls.at(dlsym(RTLD_DEFAULT,"sendfile"))==1);
    stop_for_test();
    // Off before start / after host stop preserves libc behavior.
    fd=socket(AF_INET,SOCK_DGRAM,0);
    assert(wrap<ST>("sendto")(fd,"x",1,0,reinterpret_cast<sockaddr*>(&a),sizeof(a))==1); close(fd);
    std::cout << "ip_guard tests passed (CIDR validation belongs to Java)\n";
}

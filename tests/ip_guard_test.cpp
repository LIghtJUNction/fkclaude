#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "ip_guard.hpp"
#include <arpa/inet.h>
#include <cassert>
#include <cerrno>
#include <cstring>
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
std::map<void*, std::string> names;
std::string missing, failure;
void* resolver(const char* name) {
    if (missing == name) return nullptr;
    void* p = dlsym(RTLD_DEFAULT, name); assert(p); names[p] = name; return p;
}
int hook(void* target, void* replacement, void** original) {
    if (names.at(target) == failure) return -1;
    *original = target; wrappers[names.at(target)] = replacement;
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
    missing="sendmmsg"; assert(!start()); assert(wrappers.empty());
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
    stop_for_test();
    // Off before start / after host stop preserves libc behavior.
    fd=socket(AF_INET,SOCK_DGRAM,0);
    assert(wrap<ST>("sendto")(fd,"x",1,0,reinterpret_cast<sockaddr*>(&a),sizeof(a))==1); close(fd);
    std::cout << "ip_guard tests passed (CIDR validation belongs to Java)\n";
}

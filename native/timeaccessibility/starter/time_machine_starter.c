// SPDX-License-Identifier: Apache-2.0
// Process bootstrap design derived from Shizuku's starter.cpp.
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>
#define SHELL_UID 2000
static const char *arg_value(int argc,char **argv,const char *prefix){size_t n=strlen(prefix);for(int i=1;i<argc;i++)if(strncmp(argv[i],prefix,n)==0)return argv[i]+n;return NULL;}
static void detach_stdio(void){int fd=open("/dev/null",O_RDWR);if(fd<0)return;dup2(fd,0);dup2(fd,1);dup2(fd,2);if(fd>2)close(fd);}
int main(int argc,char **argv){
 if(getuid()!=SHELL_UID)return 6;
 const char *apk=arg_value(argc,argv,"--apk=");
 const char *cls=arg_value(argc,argv,"--class=");
 const char *name=arg_value(argc,argv,"--name=");
 const char *token=arg_value(argc,argv,"--token=");
 const char *port=arg_value(argc,argv,"--port=");
 if(!apk||!*apk||!cls||!*cls||!name||!*name||!token||!*token||!port||!*port)return 2;
 if(access(apk,R_OK)!=0)return 7;
 pid_t pid=fork(); if(pid<0)return 4;
 if(pid==0){
  if(setsid()<0)_exit(4); chdir("/"); detach_stdio();
  if(setenv("CLASSPATH",apk,1)!=0)_exit(3);
  char cp[PATH_MAX+32],nice[256]; snprintf(cp,sizeof(cp),"-Djava.class.path=%s",apk); snprintf(nice,sizeof(nice),"--nice-name=%s",name);
  char *args[]={"/system/bin/app_process",cp,"/system/bin",nice,(char*)cls,(char*)token,(char*)port,NULL};
  execv(args[0],args); _exit(5);
 }
 printf("STARTED pid=%d\n",pid); fflush(stdout); return 0;
}

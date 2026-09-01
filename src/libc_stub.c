// Link-time-only bionic stub. This file is never packaged.
typedef unsigned long pthread_t;
int system(const char *x) { (void)x; return 0; }
__attribute__((noreturn)) void _exit(int x) { (void)x; for (;;) {} }
void *malloc(unsigned long n) { (void)n; return (void*)0; }
void free(void *p) { (void)p; }
unsigned long strlen(const char *s) { (void)s; return 0; }
char *strcpy(char *d, const char *s) { (void)s; return d; }
char *strncpy(char *d, const char *s, unsigned long n) { (void)s; (void)n; return d; }
int snprintf(char *s, unsigned long n, const char *f, ...) { (void)s; (void)n; (void)f; return 0; }
int pthread_create(pthread_t *t, const void *a, void *(*fn)(void*), void *arg) { (void)t; (void)a; (void)fn; (void)arg; return 0; }
int pthread_detach(pthread_t t) { (void)t; return 0; }
unsigned int sleep(unsigned int s) { return s; }

/* POSIX shm_open/shm_unlink for Android, where <sys/mman.h> may not declare them.
 * Implemented as regular files under TMPDIR (set by the app to its cache dir).
 */
#ifndef NIMBUS_ANDROID_SHM_H
#define NIMBUS_ANDROID_SHM_H
#ifdef __ANDROID__
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#ifndef SHM_ANON
static inline void nimbus_shm_path(char *out, size_t n, const char *name)
{
    const char *dir = getenv("TMPDIR");
    if (!dir || !dir[0]) {
        dir = getenv("HOME");
    }
    if (!dir || !dir[0]) {
        dir = "/data/local/tmp";
    }
    if (name && name[0] == '/') {
        name++;
    }
    snprintf(out, n, "%s/nimbus-shm-%s", dir, name ? name : "anon");
}
static inline int shm_open(const char *name, int oflag, mode_t mode)
{
    char path[512];
    nimbus_shm_path(path, sizeof path, name);
    return open(path, oflag, mode);
}
static inline int shm_unlink(const char *name)
{
    char path[512];
    nimbus_shm_path(path, sizeof path, name);
    return unlink(path);
}
#endif
#endif
#endif

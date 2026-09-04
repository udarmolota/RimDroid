#ifndef RIMDROID_MG_CAPS_H
#define RIMDROID_MG_CAPS_H

typedef void* (*rd_mg_resolver)(void* handle, const char* name);

/* Called once, with MG's primary context current, before starting the guest. */
int rimdroid_mg_caps_init(void* handle, rd_mg_resolver resolve);
/* Only replaces addresses belonging to the initialized MG library. */
void* rimdroid_mg_caps_override(const char* name, void* address);

#endif

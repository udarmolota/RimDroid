#ifndef RIMDROID_EMULATION_H
#define RIMDROID_EMULATION_H

/**
 * Set up box64 page size and env variables.
 * Called once before rimdroid_run_elf.
 * Returns 0 on success.
 */
int rimdroid_emulation_init();

/**
 * Load and execute an x86_64 ELF via box64.
 *
 * @param path  absolute path to the ELF (also argv[0])
 * @param argc  total argument count including path at index 0
 * @param argv  argv[0]=path, argv[1..]=extra args
 * @return box64 exit code, or -1 on init failure
 */
int rimdroid_run_elf(const char* path, int argc, const char** argv);

#endif // RIMDROID_EMULATION_H

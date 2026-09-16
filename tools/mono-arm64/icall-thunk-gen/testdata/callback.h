#ifndef ICALL_THUNK_TEST_CALLBACK_H
#define ICALL_THUNK_TEST_CALLBACK_H

#include <stdint.h>

uint64_t RunFunctionFmt(uintptr_t fnc, const char* fmt, ...);
double RunFunctionFmtD(uintptr_t fnc, const char* fmt, ...);

#endif

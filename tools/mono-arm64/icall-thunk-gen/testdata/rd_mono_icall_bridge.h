#ifndef ICALL_THUNK_TEST_BRIDGE_H
#define ICALL_THUNK_TEST_BRIDGE_H

/* Compile-check stand-in for the bridge wrappedlibmonobdwgc.c defines before including the thunks. */
typedef struct rd_icall_frame_s {
    void* emu;
} rd_icall_frame_t;

void rd_icall_enter(rd_icall_frame_t* frame, int index);
int rd_icall_leave(rd_icall_frame_t* frame);
float rd_icall_float_result(rd_icall_frame_t* frame);

#endif

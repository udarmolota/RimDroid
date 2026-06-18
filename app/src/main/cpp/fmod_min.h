// fmod_min.h — minimal FMOD Core C API subset for OFFLINE FSB5→PCM decoding.
//
// We do NOT have the FMOD SDK headers; we only ship the prebuilt ARM64 libfmod.so
// (in assets/bundles/libs.tar.xz). This header declares ONLY the handful of symbols
// the decoder spike needs, with the exact, stable FMOD Core ABI (verified against
// pyfmodex's ctypes declarations, which decode RimWorld's FSB5-Vorbis clips correctly).
//
// Used purely as an on-device offline decoder (NOSOUND output) to turn the user's OWN
// embedded RimWorld AudioClips (FSB5-Vorbis) into PCM WAV — bypassing box64's broken
// Vorbis decode. Nothing here plays audio.
//
// Symbols are resolved at runtime via dlopen/dlsym (see fmod_decode_spike.c), so this
// header only provides the function-pointer signatures + structs/enums/flags.

#ifndef RIMDROID_FMOD_MIN_H
#define RIMDROID_FMOD_MIN_H

#include <stdint.h>

typedef struct FMOD_SYSTEM FMOD_SYSTEM;
typedef struct FMOD_SOUND  FMOD_SOUND;

typedef int          FMOD_RESULT;        // 0 == FMOD_OK
typedef unsigned int FMOD_MODE;
typedef unsigned int FMOD_INITFLAGS;
typedef unsigned int FMOD_TIMEUNIT;
typedef int          FMOD_OUTPUTTYPE;
typedef int          FMOD_SOUND_TYPE;
typedef int          FMOD_SOUND_FORMAT;
typedef int          FMOD_CHANNELORDER;

#define FMOD_OK 0

// FMOD_MODE flags (verified)
#define FMOD_LOOP_OFF      0x00000001
#define FMOD_CREATESAMPLE  0x00000100
#define FMOD_OPENMEMORY    0x00000800
#define FMOD_ACCURATETIME  0x00004000

// FMOD_OUTPUTTYPE
#define FMOD_OUTPUTTYPE_NOSOUND      2
#define FMOD_OUTPUTTYPE_NOSOUND_NRT  4

// FMOD_INITFLAGS
#define FMOD_INIT_NORMAL 0x00000000

// FMOD_SOUND_FORMAT
#define FMOD_SOUND_FORMAT_PCM16    2
#define FMOD_SOUND_FORMAT_PCMFLOAT 5

// FMOD_TIMEUNIT
#define FMOD_TIMEUNIT_PCMBYTES 0x00000004

// FMOD_CREATESOUNDEXINFO — full struct so sizeof matches what FMOD checks against
// (cbsize must equal sizeof(this)). Field order/types per the canonical FMOD ABI.
// Pointer/callback fields are declared as void* (8 bytes, arm64) — we leave them NULL.
typedef struct FMOD_CREATESOUNDEXINFO {
    int           cbsize;
    unsigned int  length;
    unsigned int  fileoffset;
    int           numchannels;
    int           defaultfrequency;
    FMOD_SOUND_FORMAT format;
    unsigned int  decodebuffersize;
    int           initialsubsound;
    int           numsubsounds;
    int          *inclusionlist;
    int           inclusionlistnum;
    void         *pcmreadcallback;
    void         *pcmsetposcallback;
    void         *nonblockcallback;
    const char   *dlsname;
    const char   *encryptionkey;
    int           maxpolyphony;
    void         *userdata;
    FMOD_SOUND_TYPE suggestedsoundtype;
    void         *useropen;
    void         *userclose;
    void         *userread;
    void         *userseek;
    void         *userasyncread;
    void         *userasynccancel;
    void         *fileuserdata;
    int           filebuffersize;
    FMOD_CHANNELORDER channelorder;
    void         *initialsoundgroup;
    unsigned int  initialseekposition;
    int           initialseekpostype;
    int           ignoresetfilesystem;
    unsigned int  audioqueuepolicy;
    unsigned int  minmidigranularity;
    int           nonblockthreadid;
    void         *fsbguid;
} FMOD_CREATESOUNDEXINFO;

// Function-pointer typedefs (resolved via dlsym).
typedef FMOD_RESULT (*pfn_System_Create)(FMOD_SYSTEM **system, unsigned int headerversion);
typedef FMOD_RESULT (*pfn_System_SetOutput)(FMOD_SYSTEM *system, FMOD_OUTPUTTYPE output);
typedef FMOD_RESULT (*pfn_System_Init)(FMOD_SYSTEM *system, int maxchannels, FMOD_INITFLAGS flags, void *extradriverdata);
typedef FMOD_RESULT (*pfn_System_CreateSound)(FMOD_SYSTEM *system, const char *name_or_data, FMOD_MODE mode, FMOD_CREATESOUNDEXINFO *exinfo, FMOD_SOUND **sound);
typedef FMOD_RESULT (*pfn_System_Close)(FMOD_SYSTEM *system);
typedef FMOD_RESULT (*pfn_System_Release)(FMOD_SYSTEM *system);
typedef FMOD_RESULT (*pfn_Sound_GetNumSubSounds)(FMOD_SOUND *sound, int *numsubsounds);
typedef FMOD_RESULT (*pfn_Sound_GetSubSound)(FMOD_SOUND *sound, int index, FMOD_SOUND **subsound);
typedef FMOD_RESULT (*pfn_Sound_GetLength)(FMOD_SOUND *sound, unsigned int *length, FMOD_TIMEUNIT lengthtype);
typedef FMOD_RESULT (*pfn_Sound_GetFormat)(FMOD_SOUND *sound, FMOD_SOUND_TYPE *type, FMOD_SOUND_FORMAT *format, int *channels, int *bits);
typedef FMOD_RESULT (*pfn_Sound_Lock)(FMOD_SOUND *sound, unsigned int offset, unsigned int length, void **ptr1, void **ptr2, unsigned int *len1, unsigned int *len2);
typedef FMOD_RESULT (*pfn_Sound_Unlock)(FMOD_SOUND *sound, void *ptr1, void *ptr2, unsigned int len1, unsigned int len2);
typedef FMOD_RESULT (*pfn_Sound_Release)(FMOD_SOUND *sound);

#endif // RIMDROID_FMOD_MIN_H

#include <android/log.h>
#include <jni.h>

#define LOG_TAG "RimDroid/XServerJNI"
#define ARRAY_SIZE(a) ((jint)(sizeof(a) / sizeof((a)[0])))

/* @CriticalNative implementations use the critical ABI: no JNIEnv or jclass. */
extern void Java_com_rimdroid_xconnector_XConnectorEpoll_closeFd(jint fd);

extern jbyte Java_com_rimdroid_xconnector_XInputStream_readByte(jlong nativePtr);
extern jshort Java_com_rimdroid_xconnector_XInputStream_readShort(jlong nativePtr);
extern jint Java_com_rimdroid_xconnector_XInputStream_readInt(jlong nativePtr);
extern jlong Java_com_rimdroid_xconnector_XInputStream_readLong(jlong nativePtr);
extern void Java_com_rimdroid_xconnector_XInputStream_skip(jlong nativePtr, jint length);
extern jint Java_com_rimdroid_xconnector_XInputStream_available(jlong nativePtr);
extern jint Java_com_rimdroid_xconnector_XInputStream_getActivePosition(jlong nativePtr);
extern void Java_com_rimdroid_xconnector_XInputStream_setActivePosition(
        jlong nativePtr, jint activePosition);
extern jint Java_com_rimdroid_xconnector_XInputStream_getAncillaryFd(jlong nativePtr);

extern void Java_com_rimdroid_xconnector_XOutputStream_setAncillaryFd(
        jlong nativePtr, jint ancillaryFd);
extern void Java_com_rimdroid_xconnector_XOutputStream_writeByte(
        jlong nativePtr, jbyte value);
extern void Java_com_rimdroid_xconnector_XOutputStream_writeShort(
        jlong nativePtr, jshort value);
extern void Java_com_rimdroid_xconnector_XOutputStream_writeInt(
        jlong nativePtr, jint value);
extern void Java_com_rimdroid_xconnector_XOutputStream_writeLong(
        jlong nativePtr, jlong value);
extern void Java_com_rimdroid_xconnector_XOutputStream_writePad(
        jlong nativePtr, jint length);
extern jint Java_com_rimdroid_xconnector_XOutputStream_length(jlong nativePtr);

static const JNINativeMethod connector_methods[] = {
        {"closeFd", "(I)V", (void*)Java_com_rimdroid_xconnector_XConnectorEpoll_closeFd},
};

static const JNINativeMethod input_methods[] = {
        {"readByte", "(J)B", (void*)Java_com_rimdroid_xconnector_XInputStream_readByte},
        {"readShort", "(J)S", (void*)Java_com_rimdroid_xconnector_XInputStream_readShort},
        {"readInt", "(J)I", (void*)Java_com_rimdroid_xconnector_XInputStream_readInt},
        {"readLong", "(J)J", (void*)Java_com_rimdroid_xconnector_XInputStream_readLong},
        {"skip", "(JI)V", (void*)Java_com_rimdroid_xconnector_XInputStream_skip},
        {"available", "(J)I", (void*)Java_com_rimdroid_xconnector_XInputStream_available},
        {"getActivePosition", "(J)I",
         (void*)Java_com_rimdroid_xconnector_XInputStream_getActivePosition},
        {"setActivePosition", "(JI)V",
         (void*)Java_com_rimdroid_xconnector_XInputStream_setActivePosition},
        {"getAncillaryFd", "(J)I",
         (void*)Java_com_rimdroid_xconnector_XInputStream_getAncillaryFd},
};

static const JNINativeMethod output_methods[] = {
        {"setAncillaryFd", "(JI)V",
         (void*)Java_com_rimdroid_xconnector_XOutputStream_setAncillaryFd},
        {"writeByte", "(JB)V", (void*)Java_com_rimdroid_xconnector_XOutputStream_writeByte},
        {"writeShort", "(JS)V", (void*)Java_com_rimdroid_xconnector_XOutputStream_writeShort},
        {"writeInt", "(JI)V", (void*)Java_com_rimdroid_xconnector_XOutputStream_writeInt},
        {"writeLong", "(JJ)V", (void*)Java_com_rimdroid_xconnector_XOutputStream_writeLong},
        {"writePad", "(JI)V", (void*)Java_com_rimdroid_xconnector_XOutputStream_writePad},
        {"length", "(J)I", (void*)Java_com_rimdroid_xconnector_XOutputStream_length},
};

static int register_methods(JNIEnv* env, const char* class_name,
                            const JNINativeMethod* methods, jint method_count) {
    jclass cls = (*env)->FindClass(env, class_name);
    if (cls == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "FindClass failed for %s", class_name);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        return JNI_ERR;
    }

    if ((*env)->RegisterNatives(env, cls, methods, method_count) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "RegisterNatives failed for %s", class_name);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        (*env)->DeleteLocalRef(env, cls);
        return JNI_ERR;
    }

    (*env)->DeleteLocalRef(env, cls);
    return JNI_OK;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;

    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "GetEnv(JNI_VERSION_1_6) failed");
        return JNI_ERR;
    }

    if (register_methods(env, "com/rimdroid/xconnector/XConnectorEpoll",
                         connector_methods, ARRAY_SIZE(connector_methods)) != JNI_OK ||
        register_methods(env, "com/rimdroid/xconnector/XInputStream",
                         input_methods, ARRAY_SIZE(input_methods)) != JNI_OK ||
        register_methods(env, "com/rimdroid/xconnector/XOutputStream",
                         output_methods, ARRAY_SIZE(output_methods)) != JNI_OK) {
        return JNI_ERR;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "Registered 17 @CriticalNative X-server methods");
    return JNI_VERSION_1_6;
}

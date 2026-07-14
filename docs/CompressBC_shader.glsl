#version 430
#extension GL_ARB_shading_language_420pack : require

#define HLSLCC_ENABLE_UNIFORM_BUFFERS 1
#if HLSLCC_ENABLE_UNIFORM_BUFFERS
#define UNITY_UNIFORM
#else
#define UNITY_UNIFORM uniform
#endif
#define UNITY_SUPPORTS_UNIFORM_LOCATION 1
#if UNITY_SUPPORTS_UNIFORM_LOCATION
#define UNITY_LOCATION(x) layout(location = x)
#define UNITY_BINDING(x) layout(binding = x, std140)
#else
#define UNITY_LOCATION(x)
#define UNITY_BINDING(x) layout(std140)
#endif
precise vec4 u_xlat_precise_vec4;
precise ivec4 u_xlat_precise_ivec4;
precise bvec4 u_xlat_precise_bvec4;
precise uvec4 u_xlat_precise_uvec4;
vec4 ImmCB_5[17];
UNITY_BINDING(0) uniform CGlobals {
	float _Quality;
	uint _mipLevel;
};
UNITY_LOCATION(0) uniform  sampler2D _Source;
writeonly layout(binding=0) uniform uimage2D _Target;
vec4 u_xlat0;
ivec2 u_xlati0;
uvec4 u_xlatu0;
bool u_xlatb0;
vec4 u_xlat1;
ivec2 u_xlati1;
uvec4 u_xlatu1;
bvec2 u_xlatb1;
vec4 u_xlat2;
ivec2 u_xlati2;
uvec4 u_xlatu2;
bool u_xlatb2;
vec3 u_xlat3;
int u_xlati3;
uvec4 u_xlatu3;
bool u_xlatb3;
vec4 u_xlat4;
ivec2 u_xlati4;
uvec4 u_xlatu4;
bvec4 u_xlatb4;
vec4 u_xlat5;
int u_xlati5;
uvec3 u_xlatu5;
bvec3 u_xlatb5;
vec4 u_xlat6;
uint u_xlatu6;
bvec3 u_xlatb6;
vec4 u_xlat7;
vec3 u_xlat8;
vec3 u_xlat9;
vec3 u_xlat10;
uint u_xlatu10;
vec3 u_xlat11;
vec3 u_xlat12;
float u_xlat13;
vec3 u_xlat14;
vec3 u_xlat15;
uint u_xlatu15;
bool u_xlatb15;
vec2 u_xlat16;
uint u_xlatu16;
bool u_xlatb16;
vec3 u_xlat17;
uint u_xlatu17;
bool u_xlatb17;
float u_xlat18;
bool u_xlatb18;
float u_xlat19;
float u_xlat20;
float u_xlat21;
vec3 u_xlat22;
int u_xlati23;
float u_xlat24;
float u_xlat25;
float u_xlat26;
bool u_xlatb26;
vec3 u_xlat27;
ivec3 u_xlati27;
uvec3 u_xlatu27;
bool u_xlatb27;
float u_xlat28;
float u_xlat29;
int u_xlati29;
uvec2 u_xlatu29;
bool u_xlatb29;
float u_xlat30;
bool u_xlatb30;
vec2 u_xlat31;
int u_xlati31;
float u_xlat32;
int u_xlati32;
uint u_xlatu32;
bool u_xlatb32;
float u_xlat33;
float u_xlat34;
float u_xlat35;
float u_xlat36;
float u_xlat37;
bool u_xlatb37;
float u_xlat38;
float u_xlat39;
float u_xlat40;
vec3 u_xlat41;
bool u_xlatb41;
vec3 u_xlat42;
bool u_xlatb42;
float u_xlat43;
bool u_xlatb43;
float u_xlat44;
bool u_xlatb44;
float u_xlat45;
bool u_xlatb45;
float u_xlat46;
float u_xlat47;
float u_xlat48;
vec3 u_xlat50;
bool u_xlatb50;
int u_xlati51;
vec2 u_xlat52;
float u_xlat53;
bool u_xlatb53;
float u_xlat54;
int u_xlati54;
uvec2 u_xlatu54;
bool u_xlatb54;
vec2 u_xlat56;
int u_xlati56;
uvec2 u_xlatu56;
bool u_xlatb56;
float u_xlat57;
uvec2 u_xlatu57;
float u_xlat58;
int u_xlati58;
uint u_xlatu58;
float u_xlat59;
int u_xlati59;
uint u_xlatu59;
bool u_xlatb59;
float u_xlat60;
uint u_xlatu60;
bool u_xlatb60;
float u_xlat61;
float u_xlat62;
float u_xlat63;
float u_xlat64;
float u_xlat65;
float u_xlat66;
vec2 u_xlat67;
float u_xlat68;
float u_xlat69;
float u_xlat70;
float u_xlat71;
bool u_xlatb71;
float u_xlat72;
float u_xlat73;
float u_xlat74;
float u_xlat75;
float u_xlat78;
bool u_xlatb78;
vec2 u_xlat79;
float u_xlat81;
int u_xlati81;
uint u_xlatu81;
bool u_xlatb81;
float u_xlat83;
int u_xlati83;
uint u_xlatu83;
bool u_xlatb83;
float u_xlat84;
int u_xlati84;
uint u_xlatu84;
bool u_xlatb84;
float u_xlat85;
int u_xlati85;
uint u_xlatu85;
bool u_xlatb85;
float u_xlat86;
int u_xlati86;
uint u_xlatu86;
bool u_xlatb86;
float u_xlat87;
bool u_xlatb87;
float u_xlat88;
bool u_xlatb88;
float u_xlat89;
uint u_xlatu89;
bool u_xlatb89;
float u_xlat90;
bool u_xlatb90;
float u_xlat91;
bool u_xlatb91;
float u_xlat92;
float u_xlat93;
float u_xlat94;
float u_xlat95;
float u_xlat96;
int u_xlati96;
bool u_xlatb96;
float u_xlat97;
int u_xlati97;
bool u_xlatb97;
float u_xlat98;
bool u_xlatb98;
float u_xlat99;
float u_xlat100;
float u_xlat101;
float u_xlat102;
float u_xlat103;
float u_xlat105;
float u_xlat106;
bool u_xlatb106;
vec4 TempArray0[16];
vec4 TempArray1[16];
vec4 TempArray2[2];
vec4 TempArray3[16];
vec4 TempArray4[16];
vec4 TempArray5[16];
vec4 TempArray6[127];
vec4 TempArray7[16];
vec4 TempArray8[16];
vec4 TempArray9[16];
vec4 TempArray10[16];
vec4 TempArray11[16];
vec4 TempArray12[16];
vec4 TempArray13[16];
vec4 TempArray14[16];
vec4 TempArray15[16];
vec4 TempArray16[16];
vec4 TempArray17[16];
vec4 TempArray18[16];
vec4 TempArray19[4];
vec4 TempArray20[16];
vec4 TempArray21[16];
vec4 TempArray22[16];
vec4 TempArray23[16];
vec4 TempArray24[16];
vec4 TempArray25[31];
vec4 TempArray26[16];
vec4 TempArray27[16];
vec4 TempArray28[16];
vec4 TempArray29[16];
vec4 TempArray30[16];
vec4 TempArray31[16];
vec4 TempArray32[16];
vec4 TempArray33[3];
vec4 TempArray34[16];
vec4 TempArray35[16];
vec4 TempArray36[4];
vec4 TempArray37[4];
vec4 TempArray38[16];
vec4 TempArray39[16];
vec4 TempArray40[16];
vec4 TempArray41[16];
vec4 TempArray42[16];
vec4 TempArray43[4];
vec4 TempArray44[16];
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
uint uint_bitfieldExtract(uint value, int offset, int bits) { return (value >> uint(offset)) & uint(~(int(~0) << uint(bits))); }

void main()
{
ImmCB_5[0] = vec4(0.0,0.0,0.0,0.0);
ImmCB_5[1] = vec4(-1.0,0.0,0.0,0.0);
ImmCB_5[2] = vec4(1.0,0.0,0.0,0.0);
ImmCB_5[3] = vec4(-2.0,0.0,0.0,0.0);
ImmCB_5[4] = vec4(2.0,0.0,0.0,0.0);
ImmCB_5[5] = vec4(-3.0,0.0,0.0,0.0);
ImmCB_5[6] = vec4(3.0,0.0,0.0,0.0);
ImmCB_5[7] = vec4(-4.0,0.0,0.0,0.0);
ImmCB_5[8] = vec4(4.0,0.0,0.0,0.0);
ImmCB_5[9] = vec4(-5.0,0.0,0.0,0.0);
ImmCB_5[10] = vec4(5.0,0.0,0.0,0.0);
ImmCB_5[11] = vec4(-6.0,0.0,0.0,0.0);
ImmCB_5[12] = vec4(6.0,0.0,0.0,0.0);
ImmCB_5[13] = vec4(-7.0,0.0,0.0,0.0);
ImmCB_5[14] = vec4(7.0,0.0,0.0,0.0);
ImmCB_5[15] = vec4(-8.0,0.0,0.0,0.0);
ImmCB_5[16] = vec4(8.0,0.0,0.0,0.0);
    u_xlati0.xy = ivec2(gl_GlobalInvocationID.xy) << (ivec2(2, 2) & int(0x1F));
    u_xlatu1.zw = uvec2(uvec2(_mipLevel, _mipLevel));
    for(uint u_xlatu_loop_1 = 0u ; u_xlatu_loop_1<16u ; u_xlatu_loop_1++)
    {
        u_xlatu2.x = u_xlatu_loop_1 & 3u;
        u_xlatu2.y = u_xlatu_loop_1 >> (2u & uint(0x1F));
        u_xlatu1.xy = uvec2(u_xlati0.xy) + u_xlatu2.xy;
        u_xlat2 = texelFetch(_Source, ivec2(u_xlatu1.xy), int(u_xlatu1.w));
        TempArray16[int(u_xlatu_loop_1)].xyz = u_xlat2.xyz;
        TempArray0[int(u_xlatu_loop_1)].x = u_xlat2.w;
    }
    u_xlat0.x = TempArray0[0].x;
    u_xlat27.x = TempArray0[1].x;
    u_xlat54 = TempArray0[2].x;
    u_xlat81 = TempArray0[3].x;
    u_xlat1.x = TempArray0[4].x;
    u_xlat28 = TempArray0[5].x;
    u_xlat1.z = TempArray0[6].x;
    u_xlat1.w = TempArray0[7].x;
    u_xlat2.x = TempArray0[8].x;
    u_xlat29 = TempArray0[9].x;
    u_xlat56.x = TempArray0[10].x;
    u_xlat83 = TempArray0[11].x;
    u_xlat3.x = TempArray0[12].x;
    u_xlat30 = TempArray0[13].x;
    u_xlat57 = TempArray0[14].x;
    u_xlat84 = TempArray0[15].x;
    TempArray1[0].x = u_xlat0.x;
    TempArray1[1].x = u_xlat27.x;
    TempArray1[2].x = u_xlat54;
    TempArray1[3].x = u_xlat81;
    TempArray1[4].x = u_xlat1.x;
    TempArray1[5].x = u_xlat28;
    TempArray1[6].x = u_xlat1.z;
    TempArray1[7].x = u_xlat1.w;
    TempArray1[8].x = u_xlat2.x;
    TempArray1[9].x = u_xlat29;
    TempArray1[10].x = u_xlat56.x;
    TempArray1[11].x = u_xlat83;
    TempArray1[12].x = u_xlat3.x;
    TempArray1[13].x = u_xlat30;
    TempArray1[14].x = u_xlat57;
    TempArray1[15].x = u_xlat84;
    u_xlatb4.x = _Quality<0.75;
    if(u_xlatb4.x){
        u_xlat31.xy = u_xlat0.xx;
        for(uint u_xlatu_loop_2 = 1u ; u_xlatu_loop_2<16u ; u_xlatu_loop_2++)
        {
            u_xlat5.x = TempArray1[int(u_xlatu_loop_2)].x;
            u_xlat31.x = min(u_xlat31.x, u_xlat5.x);
            u_xlat31.y = max(u_xlat31.y, u_xlat5.x);
        }
    }
    if(!u_xlatb4.x){
        TempArray2[0].x = 0.0;
        TempArray2[1].x = 1.0;
        for(uint u_xlatu_loop_3 = 0u ; u_xlatu_loop_3<16u ; u_xlatu_loop_3++)
        {
            TempArray4[int(u_xlatu_loop_3)].x = 0.0;
            TempArray3[int(u_xlatu_loop_3)].x = 0.0;
        }
        for(uint u_xlatu_loop_4 = 0u ; u_xlatu_loop_4<16u ; u_xlatu_loop_4++)
        {
            u_xlat5.x = TempArray1[int(u_xlatu_loop_4)].x;
            TempArray5[int(u_xlatu_loop_4)].x = u_xlat5.x;
        }
        for(uint u_xlatu_loop_5 = 0u ; u_xlatu_loop_5<16u ; u_xlatu_loop_5++)
        {
            u_xlati5 = int(u_xlatu_loop_5) << (1 & int(0x1F));
            u_xlat32 = TempArray5[int(u_xlatu_loop_5)].x;
            TempArray6[u_xlati5].x = u_xlat32;
        }
        for(uint u_xlatu_loop_6 = 1u ; u_xlatu_loop_6<16u ; u_xlatu_loop_6++)
        {
            for(uint u_xlatu_loop_7 = u_xlatu_loop_6 ; u_xlatu_loop_7>0u ; u_xlatu_loop_7 = u_xlatu_loop_7 + 4294967295u)
            {
                u_xlati32 = int(u_xlatu_loop_7) << (1 & int(0x1F));
                u_xlati59 = u_xlati32 + int(0xFFFFFFFEu);
                u_xlat86 = TempArray6[u_xlati59].x;
                u_xlat6.x = TempArray6[u_xlati32].x;
                u_xlatb86 = u_xlat6.x<u_xlat86;
                if(u_xlatb86){
                    u_xlat86 = TempArray6[u_xlati59].x;
                    TempArray6[u_xlati32].x = u_xlat86;
                    TempArray6[u_xlati59].x = u_xlat6.x;
                }
            }
        }
        for(uint u_xlatu_loop_8 = 0u ; u_xlatu_loop_8<16u ; u_xlatu_loop_8++)
        {
            u_xlati5 = int(u_xlatu_loop_8) << (1 & int(0x1F));
            u_xlat5.x = TempArray6[u_xlati5].x;
            TempArray5[int(u_xlatu_loop_8)].x = u_xlat5.x;
        }
        TempArray3[0].x = 0.0;
        u_xlat5.x = float(-2.0);
        u_xlatu32 = uint(0u);
        for(uint u_xlatu_loop_9 = 0u ; u_xlatu_loop_9<16u ; u_xlatu_loop_9++)
        {
            u_xlat59 = TempArray5[int(u_xlatu_loop_9)].x;
            u_xlatb86 = u_xlat59!=u_xlat5.x;
            if(u_xlatb86){
                TempArray3[int(u_xlatu32)].x = u_xlat59;
                TempArray4[int(u_xlatu32)].x = 1.0;
                u_xlatu32 = u_xlatu32 + 1u;
                u_xlat5.x = u_xlat59;
            } else {
                if(u_xlatu32 != uint(0)) {
                    u_xlati59 = int(u_xlatu32) + int(0xFFFFFFFFu);
                    u_xlat86 = TempArray4[u_xlati59].x;
                    u_xlat86 = u_xlat86 + 1.0;
                    TempArray4[u_xlati59].x = u_xlat86;
                }
            }
        }
        u_xlatb85 = 2u>=u_xlatu32;
        if(u_xlatb85){
            u_xlat85 = TempArray3[0].x;
            u_xlat85 = u_xlat85 * 255.0 + 0.5;
            u_xlat85 = floor(u_xlat85);
            TempArray2[0].x = u_xlat85;
            u_xlatb5.x = int(u_xlatu32)==1;
            if(u_xlatb5.x){
                u_xlat85 = u_xlat85 + 1.0;
            } else {
                u_xlat5.x = TempArray3[1].x;
                u_xlat5.x = u_xlat5.x * 255.0 + 0.5;
                u_xlat85 = floor(u_xlat5.x);
            }
            TempArray2[1].x = u_xlat85;
            u_xlati85 = 0;
        } else {
            u_xlati85 = 1;
        }
        if(u_xlati85 != 0) {
            u_xlat6.x = TempArray3[0].x;
            u_xlati85 = int(u_xlatu32) + int(0xFFFFFFFFu);
            u_xlat6.y = TempArray3[u_xlati85].x;
            u_xlat85 = (-u_xlat6.x) + u_xlat6.y;
            u_xlatb85 = 0.1875<u_xlat85;
            if(u_xlatb85){
                u_xlat85 = u_xlat6.y + u_xlat6.x;
                u_xlat85 = u_xlat85 * 0.5;
                u_xlat7 = u_xlat6.xxyy + vec4(-0.100000001, 0.100000001, 0.100000001, -0.100000001);
                u_xlatb5.x = u_xlat7.x<0.0;
                u_xlatb5.z = 1.0<u_xlat7.z;
                u_xlat5.x = (u_xlatb5.x) ? float(0.0) : u_xlat7.x;
                u_xlat5.z = (u_xlatb5.z) ? float(1.0) : u_xlat7.z;
                u_xlatb86 = u_xlat85<u_xlat7.y;
                u_xlat86 = (u_xlatb86) ? u_xlat85 : u_xlat7.y;
                u_xlatb60 = u_xlat7.w<u_xlat85;
                u_xlat85 = (u_xlatb60) ? u_xlat85 : u_xlat7.w;
                u_xlat60 = TempArray3[1].x;
                u_xlat87 = TempArray3[2].x;
                u_xlat7.x = TempArray3[3].x;
                u_xlat34 = TempArray3[4].x;
                u_xlat61 = TempArray3[5].x;
                u_xlat88 = TempArray3[6].x;
                u_xlat8.x = TempArray3[7].x;
                u_xlat35 = TempArray3[8].x;
                u_xlat62 = TempArray3[9].x;
                u_xlat89 = TempArray3[10].x;
                u_xlat9.x = TempArray3[11].x;
                u_xlat36 = TempArray3[12].x;
                u_xlat63 = TempArray3[13].x;
                u_xlat90 = TempArray3[14].x;
                u_xlat10.x = TempArray3[15].x;
                u_xlat37 = TempArray4[0].x;
                u_xlat64 = TempArray4[1].x;
                u_xlat91 = TempArray4[2].x;
                u_xlat11.x = TempArray4[3].x;
                u_xlat38 = TempArray4[4].x;
                u_xlat65 = TempArray4[5].x;
                u_xlat92 = TempArray4[6].x;
                u_xlat12.x = TempArray4[7].x;
                u_xlat39 = TempArray4[8].x;
                u_xlat66 = TempArray4[9].x;
                u_xlat93 = TempArray4[10].x;
                u_xlat13 = TempArray4[11].x;
                u_xlat40 = TempArray4[12].x;
                u_xlat67.x = TempArray4[13].x;
                u_xlat94 = TempArray4[14].x;
                u_xlat14.x = TempArray4[15].x;
                u_xlat41.x = float(0.0);
                u_xlat41.y = float(0.0);
                u_xlat41.z = float(128000.0);
                u_xlat15.x = u_xlat5.x;
                while(true){
                    u_xlatb42 = u_xlat15.x>=u_xlat86;
                    if(u_xlatb42){break;}
                    u_xlat42.xyz = u_xlat41.xyz;
                    u_xlat16.x = u_xlat5.z;
                    while(true){
                        u_xlatb43 = u_xlat16.x<u_xlat85;
                        if(u_xlatb43){break;}
                        TempArray7[0].x = u_xlat6.x;
                        TempArray7[1].x = u_xlat60;
                        TempArray7[2].x = u_xlat87;
                        TempArray7[3].x = u_xlat7.x;
                        TempArray7[4].x = u_xlat34;
                        TempArray7[5].x = u_xlat61;
                        TempArray7[6].x = u_xlat88;
                        TempArray7[7].x = u_xlat8.x;
                        TempArray7[8].x = u_xlat35;
                        TempArray7[9].x = u_xlat62;
                        TempArray7[10].x = u_xlat89;
                        TempArray7[11].x = u_xlat9.x;
                        TempArray7[12].x = u_xlat36;
                        TempArray7[13].x = u_xlat63;
                        TempArray7[14].x = u_xlat90;
                        TempArray7[15].x = u_xlat10.x;
                        TempArray8[0].x = u_xlat37;
                        TempArray8[1].x = u_xlat64;
                        TempArray8[2].x = u_xlat91;
                        TempArray8[3].x = u_xlat11.x;
                        TempArray8[4].x = u_xlat38;
                        TempArray8[5].x = u_xlat65;
                        TempArray8[6].x = u_xlat92;
                        TempArray8[7].x = u_xlat12.x;
                        TempArray8[8].x = u_xlat39;
                        TempArray8[9].x = u_xlat66;
                        TempArray8[10].x = u_xlat93;
                        TempArray8[11].x = u_xlat13;
                        TempArray8[12].x = u_xlat40;
                        TempArray8[13].x = u_xlat67.x;
                        TempArray8[14].x = u_xlat94;
                        TempArray8[15].x = u_xlat14.x;
                        u_xlat43 = (-u_xlat15.x) + u_xlat16.x;
                        u_xlat70 = u_xlat43 * 0.142857149;
                        u_xlat97 = float(1.0) / u_xlat70;
                        u_xlat17.x = float(0.0);
                        u_xlat17.y = float(0.0);
                        while(true){
                            u_xlatb71 = floatBitsToInt(u_xlat17.x)>=int(u_xlatu32);
                            if(u_xlatb71){break;}
                            u_xlat71 = TempArray7[floatBitsToInt(u_xlat17.x)].x;
                            u_xlat98 = (-u_xlat15.x) + u_xlat71;
                            u_xlatb18 = 0.0>=u_xlat98;
                            if(u_xlatb18){
                                u_xlat18 = u_xlat15.x;
                            } else {
                                u_xlat71 = (-u_xlat16.x) + u_xlat71;
                                u_xlatb71 = u_xlat71>=0.0;
                                if(u_xlatb71){
                                    u_xlat18 = u_xlat16.x;
                                } else {
                                    u_xlat71 = u_xlat43 * 0.0714285746 + u_xlat98;
                                    u_xlat71 = u_xlat97 * u_xlat71;
                                    u_xlat71 = floor(u_xlat71);
                                    u_xlat18 = u_xlat71 * u_xlat70 + u_xlat15.x;
                                }
                            }
                            u_xlat71 = TempArray7[floatBitsToInt(u_xlat17.x)].x;
                            u_xlat71 = (-u_xlat18) + u_xlat71;
                            u_xlat71 = u_xlat71 * u_xlat71;
                            u_xlat98 = TempArray8[floatBitsToInt(u_xlat17.x)].x;
                            u_xlat17.z = u_xlat71 * u_xlat98 + u_xlat17.y;
                            u_xlatb98 = u_xlat42.z<u_xlat17.z;
                            if(u_xlatb98){
                                u_xlat17.y = u_xlat42.z;
                                break;
                            }
                            u_xlat17.x = intBitsToFloat(floatBitsToInt(u_xlat17.x) + 1);
                            u_xlat17.xy = u_xlat17.xz;
                        }
                        u_xlatb43 = u_xlat17.y<u_xlat42.z;
                        if(u_xlatb43){
                            u_xlat42.x = u_xlat15.x;
                            u_xlat42.y = u_xlat16.x;
                            u_xlat42.z = u_xlat17.y;
                        }
                        u_xlat16.x = u_xlat16.x + -0.0179999992;
                    }
                    u_xlat41.xyz = u_xlat42.xyz;
                    u_xlat15.x = u_xlat15.x + 0.0179999992;
                }
                u_xlat6.xy = u_xlat41.xy;
            } else {
                u_xlat41.z = 128000.0;
            }
            u_xlat85 = TempArray3[0].x;
            u_xlat5.x = TempArray3[1].x;
            u_xlat59 = TempArray3[2].x;
            u_xlat86 = TempArray3[3].x;
            u_xlat60 = TempArray3[4].x;
            u_xlat87 = TempArray3[5].x;
            u_xlat7.x = TempArray3[6].x;
            u_xlat34 = TempArray3[7].x;
            u_xlat61 = TempArray3[8].x;
            u_xlat88 = TempArray3[9].x;
            u_xlat8.x = TempArray3[10].x;
            u_xlat35 = TempArray3[11].x;
            u_xlat62 = TempArray3[12].x;
            u_xlat89 = TempArray3[13].x;
            u_xlat9.x = TempArray3[14].x;
            u_xlat36 = TempArray3[15].x;
            u_xlat63 = TempArray4[0].x;
            u_xlat90 = TempArray4[1].x;
            u_xlat10.x = TempArray4[2].x;
            u_xlat37 = TempArray4[3].x;
            u_xlat64 = TempArray4[4].x;
            u_xlat91 = TempArray4[5].x;
            u_xlat11.x = TempArray4[6].x;
            u_xlat38 = TempArray4[7].x;
            u_xlat65 = TempArray4[8].x;
            u_xlat92 = TempArray4[9].x;
            u_xlat12.x = TempArray4[10].x;
            u_xlat39 = TempArray4[11].x;
            u_xlat66 = TempArray4[12].x;
            u_xlat93 = TempArray4[13].x;
            u_xlat13 = TempArray4[14].x;
            u_xlat40 = TempArray4[15].x;
            u_xlat67.xy = u_xlat6.xy;
            u_xlat14.x = u_xlat41.z;
            u_xlat41.x = float(0.0);
            u_xlat14.z = float(intBitsToFloat(int(0xFFFFFFFFu)));
            while(true){
                u_xlatb15 = floatBitsToInt(u_xlat41.x)>=9;
                if(u_xlatb15){break;}
                {
                    uint quo = floatBitsToUint(u_xlat41.x) / 3u;
                    uint rem = floatBitsToUint(u_xlat41.x) % 3u;
                    u_xlatu15 = quo;
                    u_xlatu16 = rem;
                }
                u_xlat15.x = ImmCB_5[int(u_xlatu15)].x * 0.00234375009 + u_xlat6.x;
                u_xlat42.x = ImmCB_5[int(u_xlatu16)].x * 0.00234375009 + u_xlat6.y;
                u_xlat16.x = max(u_xlat15.x, 0.0);
                u_xlat16.y = min(u_xlat42.x, 1.0);
                TempArray9[0].x = u_xlat85;
                TempArray9[1].x = u_xlat5.x;
                TempArray9[2].x = u_xlat59;
                TempArray9[3].x = u_xlat86;
                TempArray9[4].x = u_xlat60;
                TempArray9[5].x = u_xlat87;
                TempArray9[6].x = u_xlat7.x;
                TempArray9[7].x = u_xlat34;
                TempArray9[8].x = u_xlat61;
                TempArray9[9].x = u_xlat88;
                TempArray9[10].x = u_xlat8.x;
                TempArray9[11].x = u_xlat35;
                TempArray9[12].x = u_xlat62;
                TempArray9[13].x = u_xlat89;
                TempArray9[14].x = u_xlat9.x;
                TempArray9[15].x = u_xlat36;
                TempArray10[0].x = u_xlat63;
                TempArray10[1].x = u_xlat90;
                TempArray10[2].x = u_xlat10.x;
                TempArray10[3].x = u_xlat37;
                TempArray10[4].x = u_xlat64;
                TempArray10[5].x = u_xlat91;
                TempArray10[6].x = u_xlat11.x;
                TempArray10[7].x = u_xlat38;
                TempArray10[8].x = u_xlat65;
                TempArray10[9].x = u_xlat92;
                TempArray10[10].x = u_xlat12.x;
                TempArray10[11].x = u_xlat39;
                TempArray10[12].x = u_xlat66;
                TempArray10[13].x = u_xlat93;
                TempArray10[14].x = u_xlat13;
                TempArray10[15].x = u_xlat40;
                u_xlat15.x = (-u_xlat16.x) + u_xlat16.y;
                u_xlat42.x = u_xlat15.x * 0.142857149;
                u_xlat69 = float(1.0) / u_xlat42.x;
                u_xlati96 = 0;
                u_xlat70 = 0.0;
                while(true){
                    u_xlatb97 = u_xlati96>=int(u_xlatu32);
                    if(u_xlatb97){break;}
                    u_xlat97 = TempArray9[u_xlati96].x;
                    u_xlat17.x = (-u_xlat16.x) + u_xlat97;
                    u_xlatb44 = 0.0>=u_xlat17.x;
                    if(u_xlatb44){
                        u_xlat44 = u_xlat16.x;
                    } else {
                        u_xlat97 = (-u_xlat16.y) + u_xlat97;
                        u_xlatb97 = u_xlat97>=0.0;
                        if(u_xlatb97){
                            u_xlat44 = u_xlat16.y;
                        } else {
                            u_xlat97 = u_xlat15.x * 0.0714285746 + u_xlat17.x;
                            u_xlat97 = u_xlat69 * u_xlat97;
                            u_xlat97 = floor(u_xlat97);
                            u_xlat44 = u_xlat97 * u_xlat42.x + u_xlat16.x;
                        }
                    }
                    u_xlat97 = TempArray9[u_xlati96].x;
                    u_xlat97 = (-u_xlat44) + u_xlat97;
                    u_xlat97 = u_xlat97 * u_xlat97;
                    u_xlat17.x = TempArray10[u_xlati96].x;
                    u_xlat97 = u_xlat97 * u_xlat17.x + u_xlat70;
                    u_xlatb17 = u_xlat14.x<u_xlat97;
                    if(u_xlatb17){
                        u_xlat70 = u_xlat14.x;
                        break;
                    }
                    u_xlati96 = u_xlati96 + 1;
                    u_xlat70 = u_xlat97;
                }
                u_xlatb15 = u_xlat70<u_xlat14.x;
                if(u_xlatb15){
                    u_xlat67.xy = u_xlat16.xy;
                    u_xlat14.x = u_xlat70;
                    u_xlat14.z = u_xlat41.x;
                }
                u_xlat41.x = intBitsToFloat(floatBitsToInt(u_xlat41.x) + 1);
            }
            u_xlatb41 = floatBitsToInt(u_xlat14.z)!=int(0xFFFFFFFFu);
            if(u_xlatb41){
                u_xlat6.xy = u_xlat67.xy;
            }
            u_xlat67.xy = u_xlat6.xy;
            u_xlat41.xz = u_xlat14.xz;
            while(true){
                u_xlatb15 = floatBitsToInt(u_xlat41.z)==int(0xFFFFFFFFu);
                if(u_xlatb15){break;}
                u_xlat15.xy = u_xlat67.xy;
                u_xlat15.z = u_xlat41.x;
                u_xlat96 = 0.0;
                u_xlat41.z = intBitsToFloat(int(0xFFFFFFFFu));
                while(true){
                    u_xlatb16 = floatBitsToInt(u_xlat96)>=9;
                    if(u_xlatb16){break;}
                    {
                        uint quo = floatBitsToUint(u_xlat96) / 3u;
                        uint rem = floatBitsToUint(u_xlat96) % 3u;
                        u_xlatu16 = quo;
                        u_xlatu17 = rem;
                    }
                    u_xlat16.x = ImmCB_5[int(u_xlatu16)].x * 0.00234375009 + u_xlat67.x;
                    u_xlat43 = ImmCB_5[int(u_xlatu17)].x * 0.00234375009 + u_xlat67.y;
                    u_xlat17.x = max(u_xlat16.x, 0.0);
                    u_xlat17.y = min(u_xlat43, 1.0);
                    TempArray11[0].x = u_xlat85;
                    TempArray11[1].x = u_xlat5.x;
                    TempArray11[2].x = u_xlat59;
                    TempArray11[3].x = u_xlat86;
                    TempArray11[4].x = u_xlat60;
                    TempArray11[5].x = u_xlat87;
                    TempArray11[6].x = u_xlat7.x;
                    TempArray11[7].x = u_xlat34;
                    TempArray11[8].x = u_xlat61;
                    TempArray11[9].x = u_xlat88;
                    TempArray11[10].x = u_xlat8.x;
                    TempArray11[11].x = u_xlat35;
                    TempArray11[12].x = u_xlat62;
                    TempArray11[13].x = u_xlat89;
                    TempArray11[14].x = u_xlat9.x;
                    TempArray11[15].x = u_xlat36;
                    TempArray12[0].x = u_xlat63;
                    TempArray12[1].x = u_xlat90;
                    TempArray12[2].x = u_xlat10.x;
                    TempArray12[3].x = u_xlat37;
                    TempArray12[4].x = u_xlat64;
                    TempArray12[5].x = u_xlat91;
                    TempArray12[6].x = u_xlat11.x;
                    TempArray12[7].x = u_xlat38;
                    TempArray12[8].x = u_xlat65;
                    TempArray12[9].x = u_xlat92;
                    TempArray12[10].x = u_xlat12.x;
                    TempArray12[11].x = u_xlat39;
                    TempArray12[12].x = u_xlat66;
                    TempArray12[13].x = u_xlat93;
                    TempArray12[14].x = u_xlat13;
                    TempArray12[15].x = u_xlat40;
                    u_xlat16.x = (-u_xlat17.x) + u_xlat17.y;
                    u_xlat43 = u_xlat16.x * 0.142857149;
                    u_xlat70 = float(1.0) / u_xlat43;
                    u_xlati97 = 0;
                    u_xlat17.z = 0.0;
                    while(true){
                        u_xlatb98 = u_xlati97>=int(u_xlatu32);
                        if(u_xlatb98){break;}
                        u_xlat98 = TempArray11[u_xlati97].x;
                        u_xlat18 = (-u_xlat17.x) + u_xlat98;
                        u_xlatb45 = 0.0>=u_xlat18;
                        if(u_xlatb45){
                            u_xlat45 = u_xlat17.x;
                        } else {
                            u_xlat98 = (-u_xlat17.y) + u_xlat98;
                            u_xlatb98 = u_xlat98>=0.0;
                            if(u_xlatb98){
                                u_xlat45 = u_xlat17.y;
                            } else {
                                u_xlat98 = u_xlat16.x * 0.0714285746 + u_xlat18;
                                u_xlat98 = u_xlat70 * u_xlat98;
                                u_xlat98 = floor(u_xlat98);
                                u_xlat45 = u_xlat98 * u_xlat43 + u_xlat17.x;
                            }
                        }
                        u_xlat98 = TempArray11[u_xlati97].x;
                        u_xlat98 = (-u_xlat45) + u_xlat98;
                        u_xlat98 = u_xlat98 * u_xlat98;
                        u_xlat18 = TempArray12[u_xlati97].x;
                        u_xlat98 = u_xlat98 * u_xlat18 + u_xlat17.z;
                        u_xlatb18 = u_xlat15.z<u_xlat98;
                        if(u_xlatb18){
                            u_xlat17.z = u_xlat15.z;
                            break;
                        }
                        u_xlati97 = u_xlati97 + 1;
                        u_xlat17.z = u_xlat98;
                    }
                    u_xlatb16 = u_xlat17.z<u_xlat15.z;
                    if(u_xlatb16){
                        u_xlat15.xyz = u_xlat17.xyz;
                        u_xlat41.z = u_xlat96;
                    }
                    u_xlat96 = intBitsToFloat(floatBitsToInt(u_xlat96) + 1);
                }
                u_xlat41.x = u_xlat15.z;
                u_xlatb96 = floatBitsToInt(u_xlat41.z)!=int(0xFFFFFFFFu);
                if(u_xlatb96){
                    u_xlat67.xy = u_xlat15.xy;
                }
            }
            u_xlat5.xy = u_xlat67.xy * vec2(255.0, 255.0) + vec2(0.5, 0.5);
            u_xlat5.xy = floor(u_xlat5.xy);
            TempArray2[0].x = u_xlat5.x;
            TempArray2[1].x = u_xlat5.y;
        }
        u_xlat85 = TempArray2[0].x;
        u_xlat5.x = TempArray2[1].x;
        u_xlatb85 = u_xlat85==u_xlat5.x;
        if(u_xlatb85){
            u_xlatb85 = u_xlat5.x<255.0;
            if(u_xlatb85){
                u_xlat85 = u_xlat5.x + 1.0;
                TempArray2[1].x = u_xlat85;
            } else {
                u_xlatb85 = 0.0<u_xlat5.x;
                if(u_xlatb85){
                    u_xlat85 = u_xlat5.x + -1.0;
                    TempArray2[1].x = u_xlat85;
                }
            }
        }
        u_xlat31.x = TempArray2[0].x;
        u_xlat31.y = TempArray2[1].x;
    }
    TempArray13[0].x = u_xlat0.x;
    TempArray13[1].x = u_xlat27.x;
    TempArray13[2].x = u_xlat54;
    TempArray13[3].x = u_xlat81;
    TempArray13[4].x = u_xlat1.x;
    TempArray13[5].x = u_xlat28;
    TempArray13[6].x = u_xlat1.z;
    TempArray13[7].x = u_xlat1.w;
    TempArray13[8].x = u_xlat2.x;
    TempArray13[9].x = u_xlat29;
    TempArray13[10].x = u_xlat56.x;
    TempArray13[11].x = u_xlat83;
    TempArray13[12].x = u_xlat3.x;
    TempArray13[13].x = u_xlat30;
    TempArray13[14].x = u_xlat57;
    TempArray13[15].x = u_xlat84;
    if(u_xlatb4.x){
        u_xlatb0 = u_xlat31.y!=u_xlat31.x;
        if(u_xlatb0){
            u_xlat0.x = (-u_xlat31.y) + u_xlat31.x;
        } else {
            u_xlat0.x = 1.0;
        }
        u_xlat0.x = 7.0 / u_xlat0.x;
        u_xlat27.x = u_xlat31.y * (-u_xlat0.x);
        for(uint u_xlatu_loop_10 = 0u ; u_xlatu_loop_10<16u ; u_xlatu_loop_10++)
        {
            u_xlat81 = TempArray13[int(u_xlatu_loop_10)].x;
            u_xlat81 = u_xlat81 * u_xlat0.x + u_xlat27.x;
            u_xlat81 = roundEven(u_xlat81);
            u_xlat81 = uintBitsToFloat(uint(u_xlat81));
            TempArray14[int(u_xlatu_loop_10)].x = u_xlat81;
            u_xlatb1.x = u_xlatu_loop_10<5u;
            if(u_xlatb1.x){
                u_xlatb1.x = 0<floatBitsToInt(u_xlat81);
                u_xlatb1.y = floatBitsToInt(u_xlat81)==7;
                u_xlati1.xy = ivec2((uvec2(u_xlatb1.xy) * 0xFFFFFFFFu) & uvec2(1u, 4294967289u));
                u_xlati1.x = u_xlati1.y + u_xlati1.x;
                u_xlat1.x = intBitsToFloat(floatBitsToInt(u_xlat81) + u_xlati1.x);
                TempArray14[int(u_xlatu_loop_10)].x = u_xlat1.x;
            } else {
                u_xlatb1.x = 5u<u_xlatu_loop_10;
                if(u_xlatb1.x){
                    u_xlatb1.x = 0<floatBitsToInt(u_xlat81);
                    u_xlatb1.y = floatBitsToInt(u_xlat81)==7;
                    u_xlati1.xy = ivec2((uvec2(u_xlatb1.xy) * 0xFFFFFFFFu) & uvec2(1u, 4294967289u));
                    u_xlati1.x = u_xlati1.y + u_xlati1.x;
                    u_xlat81 = intBitsToFloat(floatBitsToInt(u_xlat81) + u_xlati1.x);
                    TempArray14[int(u_xlatu_loop_10)].x = u_xlat81;
                } else {
                    u_xlat81 = TempArray14[5].x;
                    u_xlatb1.x = 0<floatBitsToInt(u_xlat81);
                    u_xlatb1.y = floatBitsToInt(u_xlat81)==7;
                    u_xlati1.xy = ivec2((uvec2(u_xlatb1.xy) * 0xFFFFFFFFu) & uvec2(1u, 4294967289u));
                    u_xlati1.x = u_xlati1.y + u_xlati1.x;
                    u_xlat81 = intBitsToFloat(floatBitsToInt(u_xlat81) + u_xlati1.x);
                    TempArray14[5].x = u_xlat81;
                }
            }
        }
        u_xlat0.xy = u_xlat31.xy * vec2(255.0, 255.0);
        u_xlat0.xy = roundEven(u_xlat0.xy);
        u_xlatu0.xy = uvec2(u_xlat0.xy);
        u_xlati0.x = int(u_xlatu0.x) << (8 & int(0x1F));
        u_xlati0.x = int(u_xlatu0.y | uint(u_xlati0.x));
        u_xlati54 = u_xlati0.x;
        for(uint u_xlatu_loop_11 = 0u ; u_xlatu_loop_11<5u ; u_xlatu_loop_11++)
        {
            u_xlat81 = TempArray14[int(u_xlatu_loop_11)].x;
            u_xlati1.x = int(u_xlatu_loop_11) * 3 + 16;
            u_xlati81 = floatBitsToInt(u_xlat81) << (u_xlati1.x & int(0x1F));
            u_xlati54 = int(uint(u_xlati81) | uint(u_xlati54));
        }
        u_xlat0.x = TempArray14[5].x;
        u_xlati27.x = floatBitsToInt(u_xlat0.x) << (31 & int(0x1F));
        u_xlat1.x = uintBitsToFloat(uint(u_xlati27.x) | uint(u_xlati54));
        u_xlat0.x = intBitsToFloat(floatBitsToInt(u_xlat0.x) >> (1 & int(0x1F)));
        u_xlat1.y = u_xlat0.x;
        for(uint u_xlatu_loop_12 = 6u ; u_xlatu_loop_12<16u ; u_xlatu_loop_12++)
        {
            u_xlat54 = TempArray14[int(u_xlatu_loop_12)].x;
            u_xlati81 = int(u_xlatu_loop_12) * 3 + int(0xFFFFFFF0u);
            u_xlati54 = floatBitsToInt(u_xlat54) << (u_xlati81 & int(0x1F));
            u_xlat1.y = uintBitsToFloat(uint(u_xlati54) | floatBitsToUint(u_xlat1.y));
        }
    } else {
        for(uint u_xlatu_loop_13 = 0u ; u_xlatu_loop_13<16u ; u_xlatu_loop_13++)
        {
            TempArray14[int(u_xlatu_loop_13)].x = 0.0;
        }
        for(uint u_xlatu_loop_14 = 0u ; u_xlatu_loop_14<16u ; u_xlatu_loop_14++)
        {
            TempArray15[int(u_xlatu_loop_14)].x = 0.0;
        }
        u_xlatb0 = u_xlat31.y>=u_xlat31.x;
        if(u_xlatb0){
            u_xlat31.xy = u_xlat31.yx;
        }
        TempArray15[0].x = u_xlat31.x;
        TempArray15[1].x = u_xlat31.y;
        u_xlatu0.x = 1u;
        while(true){
            u_xlatb27 = u_xlatu0.x>=7u;
            if(u_xlatb27){break;}
            u_xlatu27.x = u_xlatu0.x + 1u;
            u_xlatu54.x = (-u_xlatu0.x) + 7u;
            u_xlat54 = float(u_xlatu54.x);
            u_xlat81 = float(u_xlatu0.x);
            u_xlat81 = u_xlat81 * u_xlat31.y;
            u_xlat54 = u_xlat31.x * u_xlat54 + u_xlat81;
            u_xlat54 = u_xlat54 * 0.142857149;
            TempArray15[1+int(u_xlatu0.x)].x = u_xlat54;
            u_xlatu0.x = u_xlatu27.x;
        }
        for(uint u_xlatu_loop_15 = 8u ; u_xlatu_loop_15<16u ; u_xlatu_loop_15++)
        {
            TempArray15[int(u_xlatu_loop_15)].x = 100000.0;
        }
        for(uint u_xlatu_loop_16 = 0u ; u_xlatu_loop_16<8u ; u_xlatu_loop_16++)
        {
            u_xlat27.x = TempArray15[int(u_xlatu_loop_16)].x;
            u_xlat27.x = u_xlat27.x + 0.5;
            u_xlat27.x = floor(u_xlat27.x);
            TempArray15[int(u_xlatu_loop_16)].x = u_xlat27.x;
        }
        for(uint u_xlatu_loop_17 = 0u ; u_xlatu_loop_17<8u ; u_xlatu_loop_17++)
        {
            u_xlat27.x = TempArray15[int(u_xlatu_loop_17)].x;
            u_xlat27.x = u_xlat27.x * 0.00392156886;
            TempArray15[int(u_xlatu_loop_17)].x = u_xlat27.x;
        }
        for(uint u_xlatu_loop_18 = 0u ; u_xlatu_loop_18<16u ; u_xlatu_loop_18++)
        {
            u_xlat27.x = TempArray13[int(u_xlatu_loop_18)].x;
            u_xlat54 = float(0.0);
            u_xlat81 = float(10000000.0);
            while(true){
                u_xlatb2 = floatBitsToUint(u_xlat54)>=8u;
                if(u_xlatb2){break;}
                u_xlat2.x = TempArray15[floatBitsToInt(u_xlat54)].x;
                u_xlat2.x = u_xlat27.x + (-u_xlat2.x);
                u_xlat2.x = u_xlat2.x * u_xlat2.x;
                u_xlatb29 = u_xlat2.x<u_xlat81;
                if(u_xlatb29){
                    TempArray14[int(u_xlatu_loop_18)].x = u_xlat54;
                    u_xlat81 = u_xlat2.x;
                }
                u_xlat54 = intBitsToFloat(floatBitsToInt(u_xlat54) + 1);
            }
        }
        u_xlatu0.xy = uvec2(u_xlat31.xy);
        u_xlati27.x = int(u_xlatu0.y) << (8 & int(0x1F));
        u_xlati0.x = int(u_xlatu0.x | uint(u_xlati27.x));
        u_xlati54 = u_xlati0.x;
        for(uint u_xlatu_loop_19 = 0u ; u_xlatu_loop_19<5u ; u_xlatu_loop_19++)
        {
            u_xlat81 = TempArray14[int(u_xlatu_loop_19)].x;
            u_xlati2.x = int(u_xlatu_loop_19) * 3 + 16;
            u_xlati81 = floatBitsToInt(u_xlat81) << (u_xlati2.x & int(0x1F));
            u_xlati54 = int(uint(u_xlati81) | uint(u_xlati54));
        }
        u_xlat0.x = TempArray14[5].x;
        u_xlati27.x = floatBitsToInt(u_xlat0.x) << (31 & int(0x1F));
        u_xlat1.x = uintBitsToFloat(uint(u_xlati27.x) | uint(u_xlati54));
        u_xlat0.x = uintBitsToFloat(uint(uint_bitfieldExtract(floatBitsToUint(u_xlat0.x), 1 & int(0x1F), 2 & int(0x1F))));
        u_xlat1.y = u_xlat0.x;
        for(uint u_xlatu_loop_20 = 6u ; u_xlatu_loop_20<16u ; u_xlatu_loop_20++)
        {
            u_xlat54 = TempArray14[int(u_xlatu_loop_20)].x;
            u_xlati81 = int(u_xlatu_loop_20) * 3 + int(0xFFFFFFF0u);
            u_xlati54 = floatBitsToInt(u_xlat54) << (u_xlati81 & int(0x1F));
            u_xlat1.y = uintBitsToFloat(uint(u_xlati54) | floatBitsToUint(u_xlat1.y));
        }
    }
    u_xlati0.x = int((0.5>=_Quality) ? 0xFFFFFFFFu : uint(0));
    u_xlat27.x = float(0.0);
    u_xlat27.y = float(0.0);
    u_xlat27.z = float(0.0);
    u_xlat3.x = float(0.0);
    u_xlat3.y = float(0.0);
    u_xlat3.z = float(0.0);
    u_xlat2.x = float(1.0);
    u_xlat2.y = float(1.0);
    u_xlat2.z = float(1.0);
    for(int u_xlati_loop_21 = int(0) ; u_xlati_loop_21<16 ; u_xlati_loop_21++)
    {
        u_xlat4.xyz = TempArray16[u_xlati_loop_21].xyz;
        u_xlat2.xyz = min(u_xlat2.xyz, u_xlat4.xyz);
        u_xlat3.xyz = max(u_xlat3.xyz, u_xlat4.xyz);
        if(u_xlati0.x == 0) {
            u_xlat4.xyz = TempArray16[u_xlati_loop_21].xyz;
            u_xlat4.xyz = u_xlat4.xyz;
            u_xlat4.xyz = clamp(u_xlat4.xyz, 0.0, 1.0);
            u_xlat84 = u_xlat4.z + u_xlat4.y;
            u_xlat4.w = u_xlat84 * 0.5;
            TempArray18[u_xlati_loop_21].xyz = u_xlat4.xyw;
            u_xlat27.xyz = u_xlat27.xyz + u_xlat4.xyw;
        }
    }
    u_xlat2.xyz = max(u_xlat2.xyz, vec3(0.0, 0.0, 0.0));
    u_xlat3.xyz = min(u_xlat3.xyz, vec3(1.0, 1.0, 1.0));
    u_xlat2.xyz = u_xlat2.xyz * vec3(31.0, 63.0, 31.0);
    u_xlat2.xyz = floor(u_xlat2.xyz);
    u_xlat3.xyz = u_xlat3.xyz * vec3(31.0, 63.0, 31.0);
    u_xlat3.xyz = ceil(u_xlat3.xyz);
    u_xlatu4.xyz = uvec3(u_xlat2.xyz);
    u_xlati83 = int(u_xlatu4.y) << (5 & int(0x1F));
    u_xlati84 = int(u_xlatu4.x) * 2048 + u_xlati83;
    u_xlatu84 = uint(u_xlati84) + u_xlatu4.z;
    u_xlatu5.xyz = uvec3(u_xlat3.xyz);
    u_xlati31 = int(u_xlatu5.y) << (5 & int(0x1F));
    u_xlati85 = int(u_xlatu5.x) * 2048 + u_xlati31;
    u_xlatu85 = uint(u_xlati85) + u_xlatu5.z;
    u_xlati32 = int((u_xlatu84<u_xlatu85) ? 0xFFFFFFFFu : uint(0));
    u_xlati86 = ~(u_xlati32);
    if(u_xlati32 != 0) {
        u_xlati32 = int(uint(u_xlati0.x) & 1u);
        u_xlat6.xyz = u_xlat2.xyz * vec3(0.0322580636, 0.0158730168, 0.0322580636);
        u_xlat3.xyz = u_xlat3.xyz * vec3(0.0322580636, 0.0158730168, 0.0322580636);
        u_xlati83 = u_xlati83 << (16 & int(0x1F));
        u_xlati83 = int(u_xlatu4.x) * 134217728 + u_xlati83;
        u_xlati83 = int(u_xlatu4.z) * 65536 + u_xlati83;
        u_xlat7.x = intBitsToFloat(u_xlati83 + int(u_xlatu85));
        u_xlat2.xyz = u_xlat2.xyz * vec3(0.0322580636, 0.0158730168, 0.0322580636) + (-u_xlat3.xyz);
        u_xlat83 = dot(u_xlat2.xyz, u_xlat2.xyz);
        u_xlat83 = 3.0 / u_xlat83;
        u_xlat2.xyz = vec3(u_xlat83) * u_xlat2.xyz;
        u_xlat4.x = dot(u_xlat3.xyz, u_xlat3.xyz);
        u_xlat3.x = dot(u_xlat3.xyz, u_xlat6.xyz);
        u_xlat3.x = (-u_xlat3.x) + u_xlat4.x;
        TempArray19[0].x = 0.0;
        TempArray19[1].x = 2.80259693e-45;
        TempArray19[2].x = 4.20389539e-45;
        TempArray19[3].x = 1.40129846e-45;
        u_xlat30 = float(0.0);
        for(uint u_xlatu_loop_22 = uint(0u) ; u_xlatu_loop_22<16u ; u_xlatu_loop_22++)
        {
            u_xlat4.xzw = TempArray16[int(u_xlatu_loop_22)].xyz;
            u_xlat4.x = dot(u_xlat4.xzw, u_xlat2.xyz);
            u_xlat4.x = u_xlat3.x * u_xlat83 + u_xlat4.x;
            u_xlat4.x = roundEven(u_xlat4.x);
            u_xlatu4.x = uint(u_xlat4.x);
            u_xlati4.x = int(u_xlatu4.x & 3u);
            u_xlat4.x = TempArray19[u_xlati4.x].x;
            if(floatBitsToUint(u_xlat4.x) != uint(0)) {
                u_xlati58 = int(u_xlatu_loop_22) << (1 & int(0x1F));
                u_xlati4.x = floatBitsToInt(u_xlat4.x) << (u_xlati58 & int(0x1F));
                u_xlat30 = uintBitsToFloat(floatBitsToUint(u_xlat30) | uint(u_xlati4.x));
            }
        }
        u_xlat7.y = u_xlat30;
        u_xlat2.xy = u_xlat7.xy;
        if(u_xlati32 == 0) {
        }
        if(u_xlati0.x == 0) {
            u_xlat27.xyz = u_xlat27.xyz * vec3(0.0625, 0.0625, 0.0625);
        }
    } else {
        u_xlati56 = u_xlati31 << (16 & int(0x1F));
        u_xlati56 = int(u_xlatu5.x) * 134217728 + u_xlati56;
        u_xlati56 = int(u_xlatu5.z) * 65536 + u_xlati56;
        u_xlat7.x = intBitsToFloat(u_xlati56 + int(u_xlatu84));
        u_xlat2.x = float(0.0);
        u_xlat2.y = float(0.0);
        u_xlat7.y = 0.0;
        u_xlati0.x = int(0xFFFFFFFFu);
    }
    u_xlati0.x = int(uint(u_xlati86) | uint(u_xlati0.x));
    if(u_xlati0.x == 0) {
        u_xlat0.x = 0.0;
        u_xlat56.x = float(0.0);
        u_xlat83 = float(0.0);
        u_xlat3.x = float(0.0);
        u_xlat3.y = float(0.0);
        u_xlat3.z = float(0.0);
        for(int u_xlati_loop_23 = int(0) ; u_xlati_loop_23<16 ; u_xlati_loop_23++)
        {
            u_xlat4.xyz = TempArray18[u_xlati_loop_23].xyz;
            u_xlat4.xyz = (-u_xlat27.xyz) + u_xlat4.xyz;
            u_xlat3.xyz = u_xlat3.xyz + abs(u_xlat4.xyz);
            u_xlatb4.xw = lessThan(vec4(0.0, 0.0, 0.0, 0.0), u_xlat4.xxxz).xw;
            if(u_xlatb4.x){
                u_xlat0.x = u_xlat0.x + u_xlat4.y;
                u_xlat83 = u_xlat83 + u_xlat4.z;
            }
            if(u_xlatb4.w){
                u_xlat56.x = u_xlat56.x + u_xlat4.y;
            }
        }
        u_xlat3.xyz = u_xlat3.xzy * vec3(0.0625, 0.0625, 0.0625);
        u_xlatb84 = u_xlat0.x<0.0;
        if(u_xlatb84){
            u_xlat3.x = (-u_xlat3.x);
        }
        u_xlatb84 = u_xlat56.x<0.0;
        if(u_xlatb84){
            u_xlat3.y = (-u_xlat3.y);
        }
        u_xlatb56 = u_xlat0.x==u_xlat56.x;
        u_xlatb0 = u_xlat0.x==0.0;
        u_xlatb0 = u_xlatb0 && u_xlatb56;
        if(u_xlatb0){
            u_xlatb0 = u_xlat83<0.0;
            if(u_xlatb0){
                u_xlat3.y = (-u_xlat3.y);
            }
        }
        u_xlat0.x = dot(u_xlat3.xyz, u_xlat3.xyz);
        u_xlatb56 = 0.0<u_xlat0.x;
        if(u_xlatb56){
            u_xlat0.x = sqrt(u_xlat0.x);
            u_xlat0.x = float(1.0) / u_xlat0.x;
        } else {
            u_xlat0.x = 1.0;
        }
        u_xlat3.xyz = u_xlat0.xxx * u_xlat3.xzy;
        u_xlat4.x = float(-3.40282347e+38);
        u_xlat4.y = float(3.40282347e+38);
        for(int u_xlati_loop_24 = int(0) ; u_xlati_loop_24<16 ; u_xlati_loop_24++)
        {
            u_xlat5.xyz = TempArray18[u_xlati_loop_24].xyz;
            u_xlat5.xyz = (-u_xlat27.xyz) + u_xlat5.xyz;
            u_xlat0.x = dot(u_xlat5.xyz, u_xlat3.xyz);
            TempArray17[u_xlati_loop_24].x = u_xlat0.x;
            u_xlatb56 = u_xlat0.x<u_xlat4.y;
            if(u_xlatb56){
                u_xlat4.y = u_xlat0.x;
            }
            u_xlatb56 = u_xlat4.x<u_xlat0.x;
            if(u_xlatb56){
                u_xlat4.x = u_xlat0.x;
            }
        }
        u_xlat0.x = u_xlat4.x + u_xlat4.y;
        u_xlat56.x = u_xlat0.x * 0.5;
        u_xlat27.xyz = u_xlat3.xyz * u_xlat56.xxx + u_xlat27.xyz;
        for(int u_xlati_loop_25 = 0 ; u_xlati_loop_25<16 ; u_xlati_loop_25++)
        {
            u_xlat83 = TempArray17[u_xlati_loop_25].x;
            u_xlat83 = (-u_xlat0.x) * 0.5 + u_xlat83;
            TempArray17[u_xlati_loop_25].x = u_xlat83;
        }
        u_xlat56.xy = (-u_xlat0.xx) * vec2(0.5, 0.5) + u_xlat4.xy;
        u_xlat4.xyw = u_xlat3.xyz * u_xlat56.yyy + u_xlat27.xyz;
        u_xlat0.xyw = u_xlat3.xyz * u_xlat56.xxx + u_xlat27.xyz;
        u_xlat4.z = u_xlat4.w * 2.0 + (-u_xlat4.y);
        u_xlat0.z = u_xlat0.w * 2.0 + (-u_xlat0.y);
        u_xlat4.xyz = u_xlat4.xyz;
        u_xlat4.xyz = clamp(u_xlat4.xyz, 0.0, 1.0);
        u_xlat0.xyz = u_xlat0.xyz;
        u_xlat0.xyz = clamp(u_xlat0.xyz, 0.0, 1.0);
        u_xlat3.xyz = u_xlat4.xyz * vec3(31.0, 63.0, 31.0);
        u_xlat3.xyz = roundEven(u_xlat3.xyz);
        u_xlat0.xyz = u_xlat0.xyz * vec3(31.0, 63.0, 31.0);
        u_xlat0.xyz = roundEven(u_xlat0.xyz);
        u_xlatu3.xyz = uvec3(u_xlat3.xyz);
        u_xlati81 = int(u_xlatu3.y) << (5 & int(0x1F));
        u_xlati81 = int(u_xlatu3.x) * 2048 + u_xlati81;
        u_xlatu0.w = uint(u_xlati81) + u_xlatu3.z;
        u_xlatu0.xyz = uvec3(u_xlat0.xyz);
        u_xlati27.x = int(u_xlatu0.y) << (5 & int(0x1F));
        u_xlati0.x = int(u_xlatu0.x) * 2048 + u_xlati27.x;
        u_xlatu0.x = uint(u_xlati0.x) + u_xlatu0.z;
        u_xlatb27 = u_xlatu0.w<u_xlatu0.x;
        if(u_xlatb27){
            u_xlati27.x = 1;
            u_xlatu0.z = u_xlatu0.x;
            u_xlatu0.x = u_xlatu0.w;
        } else {
            u_xlatb3 = int(u_xlatu0.x)==int(u_xlatu0.w);
            if(u_xlatb3){
                for(int u_xlati_loop_26 = 0 ; u_xlati_loop_26<16 ; u_xlati_loop_26++)
                {
                    TempArray17[u_xlati_loop_26].x = u_xlat56.y;
                }
            }
            u_xlati27.x = 0;
            u_xlatu0.xz = u_xlatu0.xw;
        }
        u_xlat7.x = intBitsToFloat(int(u_xlatu0.x) * 65536 + int(u_xlatu0.z));
        u_xlat54 = u_xlat56.x * 0.666666687;
        u_xlat81 = u_xlat56.x + u_xlat56.y;
        u_xlat81 = u_xlat81 * 0.5;
        u_xlat7.y = 0.0;
        for(int u_xlati_loop_27 = 0 ; u_xlati_loop_27<16 ; u_xlati_loop_27++)
        {
            u_xlat83 = TempArray17[u_xlati_loop_27].x;
            u_xlatb83 = abs(u_xlat83)>=u_xlat54;
            if(u_xlatb83){
                u_xlati83 = 0;
            } else {
                u_xlati83 = 2;
            }
            u_xlat3.x = TempArray17[u_xlati_loop_27].x;
            u_xlatb3 = u_xlat3.x>=u_xlat81;
            if(u_xlatb3){
                u_xlati83 = u_xlati83 + 1;
            }
            u_xlati83 = int(uint(u_xlati27.x) ^ uint(u_xlati83));
            u_xlati3 = u_xlati_loop_27 << (1 & int(0x1F));
            u_xlati83 = u_xlati83 << (u_xlati3 & int(0x1F));
            u_xlat7.y = uintBitsToFloat(uint(u_xlati83) | floatBitsToUint(u_xlat7.y));
        }
        u_xlatu27.x = floatBitsToUint(u_xlat7.x) & 65535u;
        u_xlatu3.xyz = uvec3(uint_bitfieldExtract(floatBitsToUint(u_xlat7.x), int(8) & int(0x1F), int(8) & int(0x1F)), uint_bitfieldExtract(floatBitsToUint(u_xlat7.x), int(3) & int(0x1F), int(13) & int(0x1F)), uint_bitfieldExtract(floatBitsToUint(u_xlat7.x), int(13) & int(0x1F), int(3) & int(0x1F)));
        u_xlati54 = int(u_xlatu3.y & 252u);
        u_xlatu81 =  uint(int(bitfieldInsert(0, floatBitsToInt(u_xlat7.x), 3 & int(0x1F), 5)));
        u_xlatu56.x =  uint(int(bitfieldInsert(int(u_xlatu3.x), int(u_xlatu3.z), 0 & int(0x1F), 3)));
        u_xlatu83 = uint(uint_bitfieldExtract(u_xlatu3.y, 6 & int(0x1F), 2 & int(0x1F)));
        u_xlatu54.x = uint(u_xlati54) + u_xlatu83;
        u_xlatu83 = u_xlatu81 >> (5u & uint(0x1F));
        u_xlatu54.y = u_xlatu81 + u_xlatu83;
        u_xlat3.x = float(u_xlatu56.x);
        u_xlat3.yz = vec2(u_xlatu54.xy);
        u_xlatu4 = uvec4(uint_bitfieldExtract(u_xlatu0.x, int(0) & int(0x1F), int(16) & int(0x1F)), uint_bitfieldExtract(u_xlatu0.x, int(8) & int(0x1F), int(8) & int(0x1F)), uint_bitfieldExtract(u_xlatu0.x, int(3) & int(0x1F), int(13) & int(0x1F)), uint_bitfieldExtract(u_xlatu0.x, int(13) & int(0x1F), int(3) & int(0x1F)));
        u_xlati0.x = int(u_xlatu4.z & 252u);
        u_xlatu54.x =  uint(int(bitfieldInsert(0, int(u_xlatu4.x), 3 & int(0x1F), 5)));
        u_xlatu0.w =  uint(int(bitfieldInsert(int(u_xlatu4.y), int(u_xlatu4.w), 0 & int(0x1F), 3)));
        u_xlatu56.x = uint(uint_bitfieldExtract(u_xlatu4.z, 6 & int(0x1F), 2 & int(0x1F)));
        u_xlatu0.x = uint(u_xlati0.x) + u_xlatu56.x;
        u_xlatu56.x = u_xlatu54.x >> (5u & uint(0x1F));
        u_xlatu0.z = u_xlatu54.x + u_xlatu56.x;
        u_xlat5.xyz = vec3(u_xlatu0.wxz);
        u_xlatb0 = u_xlatu4.x<u_xlatu27.x;
        if(u_xlatb0){
            u_xlat0.xyz = u_xlat3.xyz * vec3(2.0, 2.0, 2.0) + u_xlat5.xyz;
            u_xlat0.xyz = u_xlat0.xyz * vec3(0.333333343, 0.333333343, 0.333333343);
            u_xlat4.xyz = u_xlat5.xyz * vec3(2.0, 2.0, 2.0) + u_xlat3.xyz;
            u_xlat4.xyz = u_xlat4.xyz * vec3(0.333333343, 0.333333343, 0.333333343);
            for(uint u_xlatu_loop_28 = 0u ; u_xlatu_loop_28<16u ; u_xlatu_loop_28++)
            {
                u_xlatu56.x =  uint(int(u_xlatu_loop_28) << (1 & int(0x1F)));
                u_xlatu56.x = floatBitsToUint(u_xlat7.y) >> (u_xlatu56.x & uint(0x1F));
                u_xlati56 = int(u_xlatu56.x & 3u);
                switch(u_xlati56){
                    case 0:
                        TempArray20[int(u_xlatu_loop_28)].xyz = u_xlat3.xyz;
                        break;
                    case 1:
                        TempArray20[int(u_xlatu_loop_28)].xyz = u_xlat5.xyz;
                        break;
                    case 2:
                        TempArray20[int(u_xlatu_loop_28)].xyz = u_xlat0.xyz;
                        break;
                    case 3:
                        TempArray20[int(u_xlatu_loop_28)].xyz = u_xlat4.xyz;
                        break;
                }
            }
        } else {
            u_xlat0.xyz = u_xlat3.xyz + u_xlat5.xyz;
            u_xlat0.xyz = u_xlat0.xyz * vec3(0.5, 0.5, 0.5);
            for(uint u_xlatu_loop_29 = 0u ; u_xlatu_loop_29<16u ; u_xlatu_loop_29++)
            {
                u_xlatu56.x =  uint(int(u_xlatu_loop_29) << (1 & int(0x1F)));
                u_xlatu56.x = floatBitsToUint(u_xlat7.y) >> (u_xlatu56.x & uint(0x1F));
                u_xlati56 = int(u_xlatu56.x & 3u);
                switch(u_xlati56){
                    case 0:
                        TempArray20[int(u_xlatu_loop_29)].xyz = u_xlat3.xyz;
                        break;
                    case 1:
                        TempArray20[int(u_xlatu_loop_29)].xyz = u_xlat5.xyz;
                        break;
                    case 2:
                        TempArray20[int(u_xlatu_loop_29)].xyz = u_xlat0.xyz;
                        break;
                    case 3:
                        TempArray20[int(u_xlatu_loop_29)].xyz = vec3(0.0, 0.0, 0.0);
                        break;
                }
            }
        }
        u_xlat0.x = float(0.0);
        u_xlat27.x = float(0.0);
        u_xlat54 = float(0.0);
        for(int u_xlati_loop_30 = int(0) ; u_xlati_loop_30<16 ; u_xlati_loop_30++)
        {
            u_xlat56.x = TempArray16[u_xlati_loop_30].x;
            u_xlat56.x = u_xlat56.x * 255.0;
            u_xlat83 = TempArray16[u_xlati_loop_30].y;
            u_xlat56.y = u_xlat83 * 255.0;
            u_xlat56.xy = roundEven(u_xlat56.xy);
            u_xlat3.x = TempArray16[u_xlati_loop_30].z;
            u_xlat3.x = u_xlat3.x * 255.0;
            u_xlat3.x = roundEven(u_xlat3.x);
            u_xlat30 = TempArray20[u_xlati_loop_30].x;
            u_xlat57 = TempArray20[u_xlati_loop_30].y;
            u_xlat84 = TempArray20[u_xlati_loop_30].z;
            u_xlat56.x = u_xlat56.x + (-u_xlat30);
            u_xlat0.x = u_xlat56.x * u_xlat56.x + u_xlat0.x;
            u_xlat56.x = u_xlat56.y + (-u_xlat57);
            u_xlat27.x = u_xlat56.x * u_xlat56.x + u_xlat27.x;
            u_xlat56.x = (-u_xlat84) + u_xlat3.x;
            u_xlat54 = u_xlat56.x * u_xlat56.x + u_xlat54;
        }
        u_xlat0.x = u_xlat27.x + u_xlat0.x;
        u_xlat0.x = u_xlat54 + u_xlat0.x;
        u_xlatu27.x = floatBitsToUint(u_xlat2.x) & 65535u;
        u_xlatu54.x = floatBitsToUint(u_xlat2.x) >> (16u & uint(0x1F));
        u_xlatu3 = uvec4(uint_bitfieldExtract(floatBitsToUint(u_xlat2.x), int(8) & int(0x1F), int(8) & int(0x1F)), uint_bitfieldExtract(floatBitsToUint(u_xlat2.x), int(3) & int(0x1F), int(13) & int(0x1F)), uint_bitfieldExtract(floatBitsToUint(u_xlat2.x), int(13) & int(0x1F), int(3) & int(0x1F)), uint_bitfieldExtract(floatBitsToUint(u_xlat2.x), int(25) & int(0x1F), int(2) & int(0x1F)));
        u_xlati81 = int(u_xlatu3.y & 252u);
        u_xlatu56.x =  uint(int(bitfieldInsert(0, floatBitsToInt(u_xlat2.x), 3 & int(0x1F), 5)));
        u_xlatu56.y =  uint(int(bitfieldInsert(int(u_xlatu3.x), int(u_xlatu3.z), 0 & int(0x1F), 3)));
        u_xlatu3.x = uint(uint_bitfieldExtract(u_xlatu3.y, 6 & int(0x1F), 2 & int(0x1F)));
        u_xlatu81 = uint(u_xlati81) + u_xlatu3.x;
        u_xlatu3.x = u_xlatu56.x >> (5u & uint(0x1F));
        u_xlatu56.x = u_xlatu56.x + u_xlatu3.x;
        u_xlat3.y = float(u_xlatu81);
        u_xlat3.xz = vec2(u_xlatu56.yx);
        u_xlatu56.xy = u_xlatu54.xx >> (uvec2(8u, 3u) & uint(0x1F));
        u_xlati81 = int(u_xlatu56.y & 252u);
        u_xlatu83 =  uint(int(bitfieldInsert(0, int(u_xlatu54.x), 3 & int(0x1F), 5)));
        u_xlatu4.x = u_xlatu56.x >> (5u & uint(0x1F));
        u_xlatu56.x =  uint(int(bitfieldInsert(int(u_xlatu56.x), int(u_xlatu4.x), 0 & int(0x1F), 3)));
        u_xlatu81 = uint(u_xlati81) + u_xlatu3.w;
        u_xlatu84 = u_xlatu83 >> (5u & uint(0x1F));
        u_xlatu56.y = u_xlatu83 + u_xlatu84;
        u_xlat4.y = float(u_xlatu81);
        u_xlat4.xz = vec2(u_xlatu56.xy);
        u_xlatb27 = u_xlatu54.x<u_xlatu27.x;
        if(u_xlatb27){
            u_xlat27.xyz = u_xlat3.xyz * vec3(2.0, 2.0, 2.0) + u_xlat4.xyz;
            u_xlat27.xyz = u_xlat27.xyz * vec3(0.333333343, 0.333333343, 0.333333343);
            u_xlat5.xyz = u_xlat4.xyz * vec3(2.0, 2.0, 2.0) + u_xlat3.xyz;
            u_xlat5.xyz = u_xlat5.xyz * vec3(0.333333343, 0.333333343, 0.333333343);
            for(uint u_xlatu_loop_31 = 0u ; u_xlatu_loop_31<16u ; u_xlatu_loop_31++)
            {
                u_xlatu83 =  uint(int(u_xlatu_loop_31) << (1 & int(0x1F)));
                u_xlatu83 = floatBitsToUint(u_xlat2.y) >> (u_xlatu83 & uint(0x1F));
                u_xlati83 = int(u_xlatu83 & 3u);
                switch(u_xlati83){
                    case 0:
                        TempArray21[int(u_xlatu_loop_31)].xyz = u_xlat3.xyz;
                        break;
                    case 1:
                        TempArray21[int(u_xlatu_loop_31)].xyz = u_xlat4.xyz;
                        break;
                    case 2:
                        TempArray21[int(u_xlatu_loop_31)].xyz = u_xlat27.xyz;
                        break;
                    case 3:
                        TempArray21[int(u_xlatu_loop_31)].xyz = u_xlat5.xyz;
                        break;
                }
            }
        } else {
            u_xlat27.xyz = u_xlat3.xyz + u_xlat4.xyz;
            u_xlat27.xyz = u_xlat27.xyz * vec3(0.5, 0.5, 0.5);
            for(uint u_xlatu_loop_32 = 0u ; u_xlatu_loop_32<16u ; u_xlatu_loop_32++)
            {
                u_xlatu83 =  uint(int(u_xlatu_loop_32) << (1 & int(0x1F)));
                u_xlatu83 = floatBitsToUint(u_xlat2.y) >> (u_xlatu83 & uint(0x1F));
                u_xlati83 = int(u_xlatu83 & 3u);
                switch(u_xlati83){
                    case 0:
                        TempArray21[int(u_xlatu_loop_32)].xyz = u_xlat3.xyz;
                        break;
                    case 1:
                        TempArray21[int(u_xlatu_loop_32)].xyz = u_xlat4.xyz;
                        break;
                    case 2:
                        TempArray21[int(u_xlatu_loop_32)].xyz = u_xlat27.xyz;
                        break;
                    case 3:
                        TempArray21[int(u_xlatu_loop_32)].xyz = vec3(0.0, 0.0, 0.0);
                        break;
                }
            }
        }
        u_xlat27.x = float(0.0);
        u_xlat54 = float(0.0);
        u_xlat81 = float(0.0);
        for(int u_xlati_loop_33 = 0 ; u_xlati_loop_33<16 ; u_xlati_loop_33++)
        {
            u_xlat83 = TempArray16[u_xlati_loop_33].x;
            u_xlat83 = u_xlat83 * 255.0;
            u_xlat83 = roundEven(u_xlat83);
            u_xlat3.x = TempArray16[u_xlati_loop_33].y;
            u_xlat3.x = u_xlat3.x * 255.0;
            u_xlat30 = TempArray16[u_xlati_loop_33].z;
            u_xlat3.y = u_xlat30 * 255.0;
            u_xlat3.xy = roundEven(u_xlat3.xy);
            u_xlat57 = TempArray21[u_xlati_loop_33].x;
            u_xlat84 = TempArray21[u_xlati_loop_33].y;
            u_xlat4.x = TempArray21[u_xlati_loop_33].z;
            u_xlat83 = u_xlat83 + (-u_xlat57);
            u_xlat27.x = u_xlat83 * u_xlat83 + u_xlat27.x;
            u_xlat83 = (-u_xlat84) + u_xlat3.x;
            u_xlat54 = u_xlat83 * u_xlat83 + u_xlat54;
            u_xlat83 = u_xlat3.y + (-u_xlat4.x);
            u_xlat81 = u_xlat83 * u_xlat83 + u_xlat81;
        }
        u_xlat27.x = u_xlat54 + u_xlat27.x;
        u_xlat0.y = u_xlat81 + u_xlat27.x;
        u_xlat0.xy = u_xlat0.xy * vec2(0.020833334, 0.020833334);
        u_xlatb54 = u_xlat0.y<u_xlat0.x;
        if(u_xlatb54){
            u_xlat7.xy = u_xlat2.xy;
            u_xlat0.x = u_xlat0.y;
        }
    } else {
        u_xlat0.x = 0.0;
    }
    u_xlatb27 = 0.0<u_xlat0.x;
    u_xlatb54 = 0.75<_Quality;
    u_xlatb27 = u_xlatb54 && u_xlatb27;
    if(u_xlatb27){
        for(uint u_xlatu_loop_34 = 0u ; u_xlatu_loop_34<16u ; u_xlatu_loop_34++)
        {
            TempArray23[int(u_xlatu_loop_34)].x = 0.0;
        }
        u_xlatu27.x = uint(0u);
        u_xlatu27.y = uint(0u);
        while(true){
            u_xlatb81 = u_xlatu27.x>=16u;
            if(u_xlatb81){break;}
            u_xlat2.xyz = TempArray16[int(u_xlatu27.x)].xyz;
            TempArray22[int(u_xlatu27.x)].xyz = u_xlat2.xyz;
            u_xlat2.xyz = u_xlat2.xyz * vec3(255.0, 255.0, 255.0);
            u_xlatu2.xyz = uvec3(u_xlat2.xyz);
            u_xlati2.xy = ivec2(u_xlatu2.xy) << (ivec2(16, 8) & int(0x1F));
            u_xlati2.x = int(uint(u_xlati2.x) | 4278190080u);
            u_xlati2.x = int(uint(u_xlati2.y) | uint(u_xlati2.x));
            u_xlat2.x = uintBitsToFloat(u_xlatu2.z | uint(u_xlati2.x));
            TempArray24[int(u_xlatu27.y)].x = u_xlat2.x;
            u_xlatu27.xz = u_xlatu27.xy + uvec2(1u, 1u);
            u_xlatu27.xy = u_xlatu27.xz;
        }
        for(uint u_xlatu_loop_35 = 0u ; u_xlatu_loop_35<16u ; u_xlatu_loop_35++)
        {
            u_xlati54 = int(u_xlatu_loop_35) << (1 & int(0x1F));
            u_xlat81 = TempArray24[int(u_xlatu_loop_35)].x;
            TempArray25[u_xlati54].x = u_xlat81;
        }
        for(uint u_xlatu_loop_36 = 1u ; u_xlatu_loop_36<16u ; u_xlatu_loop_36++)
        {
            for(uint u_xlatu_loop_37 = u_xlatu_loop_36 ; u_xlatu_loop_37>0u ; u_xlatu_loop_37 = u_xlatu_loop_37 + 4294967295u)
            {
                u_xlati81 = int(u_xlatu_loop_37) << (1 & int(0x1F));
                u_xlati2.x = u_xlati81 + int(0xFFFFFFFEu);
                u_xlat29 = TempArray25[u_xlati2.x].x;
                u_xlat56.x = TempArray25[u_xlati81].x;
                u_xlatb29 = floatBitsToUint(u_xlat56.x)<floatBitsToUint(u_xlat29);
                if(u_xlatb29){
                    u_xlat29 = TempArray25[u_xlati2.x].x;
                    TempArray25[u_xlati81].x = u_xlat29;
                    TempArray25[u_xlati2.x].x = u_xlat56.x;
                }
            }
        }
        for(uint u_xlatu_loop_38 = 0u ; u_xlatu_loop_38<16u ; u_xlatu_loop_38++)
        {
            u_xlati54 = int(u_xlatu_loop_38) << (1 & int(0x1F));
            u_xlat54 = TempArray25[u_xlati54].x;
            TempArray24[int(u_xlatu_loop_38)].x = u_xlat54;
        }
        u_xlat27.x = TempArray24[0].x;
        TempArray26[0].x = u_xlat27.x;
        TempArray23[0].x = 1.0;
        u_xlat81 = u_xlat27.x;
        u_xlati2.x = 0;
        for(uint u_xlatu_loop_39 = 1u ; u_xlatu_loop_39<16u ; u_xlatu_loop_39++)
        {
            u_xlat29 = TempArray24[int(u_xlatu_loop_39)].x;
            u_xlatb56 = floatBitsToInt(u_xlat81)!=floatBitsToInt(u_xlat29);
            if(u_xlatb56){
                u_xlati56 = u_xlati2.x + 1;
                TempArray26[1+u_xlati2.x].x = u_xlat29;
                TempArray23[1+u_xlati2.x].x = 1.0;
                u_xlat81 = u_xlat29;
                u_xlati2.x = u_xlati56;
            } else {
                u_xlat29 = TempArray23[u_xlati2.x].x;
                u_xlat29 = u_xlat29 + 1.0;
                TempArray23[u_xlati2.x].x = u_xlat29;
            }
        }
        u_xlatu27.x = uint(u_xlati2.x) + 1u;
        u_xlati54 = int((2u>=u_xlatu27.x) ? 0xFFFFFFFFu : uint(0));
        if(u_xlati54 != 0) {
            u_xlat2.x = float(0.0);
            u_xlat2.y = float(0.0);
            u_xlat2.z = float(255.0);
            u_xlat3.x = float(0.0);
            u_xlat3.y = float(0.0);
            u_xlat3.z = float(255.0);
            u_xlat1.zw = u_xlat7.xy;
        } else {
            for(uint u_xlatu_loop_40 = 0u ; u_xlatu_loop_40<u_xlatu27.x ; u_xlatu_loop_40++)
            {
                u_xlat83 = TempArray26[int(u_xlatu_loop_40)].x;
                u_xlatu4.xy = uvec2(uint_bitfieldExtract(floatBitsToUint(float(u_xlat83)), int(16) & int(0x1F), int(8) & int(0x1F)), uint_bitfieldExtract(floatBitsToUint(float(u_xlat83)), int(8) & int(0x1F), int(8) & int(0x1F)));
                u_xlatu83 = floatBitsToUint(u_xlat83) & 255u;
                u_xlat4.xy = vec2(u_xlatu4.xy);
                u_xlat4.xy = u_xlat4.xy * vec2(0.00392156886, 0.00392156886);
                TempArray27[int(u_xlatu_loop_40)].z = u_xlat4.x;
                TempArray27[int(u_xlatu_loop_40)].y = u_xlat4.y;
                u_xlat83 = float(u_xlatu83);
                u_xlat83 = u_xlat83 * 0.00392156886;
                TempArray27[int(u_xlatu_loop_40)].x = u_xlat83;
            }
            u_xlat81 = TempArray23[0].x;
            u_xlat83 = TempArray23[1].x;
            u_xlat84 = TempArray23[2].x;
            u_xlat4.x = TempArray23[3].x;
            u_xlat31.x = TempArray23[4].x;
            u_xlat58 = TempArray23[5].x;
            u_xlat85 = TempArray23[6].x;
            u_xlat5.x = TempArray23[7].x;
            u_xlat32 = TempArray23[8].x;
            u_xlat59 = TempArray23[9].x;
            u_xlat86 = TempArray23[10].x;
            u_xlat6.x = TempArray23[11].x;
            u_xlat33 = TempArray23[12].x;
            u_xlat60 = TempArray23[13].x;
            u_xlat87 = TempArray23[14].x;
            u_xlat61 = TempArray23[15].x;
            TempArray28[0].x = u_xlat81;
            TempArray28[1].x = u_xlat83;
            TempArray28[2].x = u_xlat84;
            TempArray28[3].x = u_xlat4.x;
            TempArray28[4].x = u_xlat31.x;
            TempArray28[5].x = u_xlat58;
            TempArray28[6].x = u_xlat85;
            TempArray28[7].x = u_xlat5.x;
            TempArray28[8].x = u_xlat32;
            TempArray28[9].x = u_xlat59;
            TempArray28[10].x = u_xlat86;
            TempArray28[11].x = u_xlat6.x;
            TempArray28[12].x = u_xlat33;
            TempArray28[13].x = u_xlat60;
            TempArray28[14].x = u_xlat87;
            TempArray28[15].x = u_xlat61;
            for(uint u_xlatu_loop_41 = 0u ; u_xlatu_loop_41<u_xlatu27.x ; u_xlatu_loop_41++)
            {
                u_xlat4.xyz = TempArray27[int(u_xlatu_loop_41)].xyz;
                TempArray34[int(u_xlatu_loop_41)].xyz = u_xlat4.xyz;
            }
            TempArray36[2].x = 0.0;
            TempArray36[1].x = 0.0;
            TempArray36[0].x = 0.0;
            TempArray37[2].x = 0.0;
            TempArray37[1].x = 0.0;
            TempArray37[0].x = 0.0;
            TempArray33[2].x = 0.0;
            TempArray33[1].x = 0.0;
            TempArray33[0].x = 0.0;
            u_xlat4.x = float(0.0);
            u_xlat4.y = float(0.0);
            u_xlat4.z = float(0.0);
            u_xlat83 = 0.0;
            for(uint u_xlatu_loop_42 = 0u ; u_xlatu_loop_42<u_xlatu27.x ; u_xlatu_loop_42++)
            {
                u_xlat84 = TempArray34[int(u_xlatu_loop_42)].x;
                u_xlat85 = TempArray28[int(u_xlatu_loop_42)].x;
                u_xlat4.x = u_xlat84 * u_xlat85 + u_xlat4.x;
                u_xlat84 = TempArray34[int(u_xlatu_loop_42)].y;
                u_xlat4.y = u_xlat84 * u_xlat85 + u_xlat4.y;
                u_xlat84 = TempArray34[int(u_xlatu_loop_42)].z;
                u_xlat4.z = u_xlat84 * u_xlat85 + u_xlat4.z;
                u_xlat83 = u_xlat83 + u_xlat85;
            }
            u_xlat4.xyz = u_xlat4.xyz / vec3(u_xlat83);
            for(uint u_xlatu_loop_43 = 0u ; u_xlatu_loop_43<u_xlatu27.x ; u_xlatu_loop_43++)
            {
                u_xlat5.xyz = TempArray34[int(u_xlatu_loop_43)].xyz;
                u_xlat5 = (-u_xlat4.xyzx) + u_xlat5.xyzx;
                TempArray35[int(u_xlatu_loop_43)].xyz = u_xlat5.wyz;
                u_xlat6 = u_xlat5.xyzy * u_xlat5;
                u_xlat84 = TempArray28[int(u_xlatu_loop_43)].x;
                u_xlat85 = TempArray37[0].x;
                u_xlat85 = u_xlat6.x * u_xlat84 + u_xlat85;
                TempArray37[0].x = u_xlat85;
                u_xlat85 = TempArray37[1].x;
                u_xlat85 = u_xlat6.y * u_xlat84 + u_xlat85;
                TempArray37[1].x = u_xlat85;
                u_xlat85 = TempArray37[2].x;
                u_xlat85 = u_xlat6.z * u_xlat84 + u_xlat85;
                TempArray37[2].x = u_xlat85;
                u_xlat85 = TempArray36[0].x;
                u_xlat85 = u_xlat6.w * u_xlat84 + u_xlat85;
                TempArray36[0].x = u_xlat85;
                u_xlat5.xy = u_xlat5.zw * u_xlat5.yz;
                u_xlat85 = TempArray36[1].x;
                u_xlat85 = u_xlat5.x * u_xlat84 + u_xlat85;
                TempArray36[1].x = u_xlat85;
                u_xlat85 = TempArray36[2].x;
                u_xlat84 = u_xlat5.y * u_xlat84 + u_xlat85;
                TempArray36[2].x = u_xlat84;
            }
            u_xlat5.xy = vec2(u_xlat83) * vec2(6.15148092e-05, 0.000184544435);
            u_xlati83 = 0;
            u_xlatu84 = 0u;
            u_xlat85 = 0.0;
            for(uint u_xlatu_loop_44 = 0u ; u_xlatu_loop_44<3u ; u_xlatu_loop_44++)
            {
                u_xlat59 = TempArray37[int(u_xlatu_loop_44)].x;
                u_xlatb59 = u_xlat59>=u_xlat5.x;
                if(u_xlatb59){
                    u_xlati83 = u_xlati83 + 1;
                } else {
                    TempArray37[int(u_xlatu_loop_44)].x = 0.0;
                }
                u_xlat59 = TempArray37[int(u_xlatu_loop_44)].x;
                u_xlatb86 = u_xlat85<u_xlat59;
                if(u_xlatb86){
                    u_xlatu84 = u_xlatu_loop_44;
                    u_xlat85 = u_xlat59;
                }
            }
            u_xlat81 = TempArray37[0].x;
            u_xlatb81 = u_xlat81<u_xlat5.y;
            u_xlat85 = TempArray37[1].x;
            u_xlatb85 = u_xlat85<u_xlat5.y;
            u_xlatb81 = u_xlatb81 && u_xlatb85;
            u_xlat85 = TempArray37[2].x;
            u_xlatb85 = u_xlat85<u_xlat5.y;
            u_xlatb81 = u_xlatb81 && u_xlatb85;
            u_xlati81 = u_xlatb81 ? 1 : int(0);
            if(u_xlati81 == 0) {
                u_xlatb81 = u_xlati83==1;
                if(u_xlatb81){
                    TempArray33[int(u_xlatu84)].x = 1.0;
                } else {
                    u_xlatb81 = u_xlati83==2;
                    if(u_xlatb81){
                        u_xlatu5.xy = uvec2(u_xlatu84) + uvec2(1u, 2u);
                        {
                            uvec2 rem = u_xlatu5.xy % uvec2(3u, 3u);
                            u_xlatu5.xy = rem;
                        }
                        u_xlat81 = TempArray37[int(u_xlatu5.x)].x;
                        u_xlatb81 = 0.0<u_xlat81;
                        u_xlatu81 = (u_xlatb81) ? u_xlatu5.x : u_xlatu5.y;
                        u_xlatb83 = int(u_xlatu5.x)==int(u_xlatu81);
                        u_xlat85 = TempArray36[int(u_xlatu84)].x;
                        u_xlat5.x = TempArray36[int(u_xlatu5.y)].x;
                        u_xlat83 = (u_xlatb83) ? u_xlat85 : u_xlat5.x;
                        u_xlat85 = TempArray37[int(u_xlatu84)].x;
                        u_xlat83 = u_xlat83 / u_xlat85;
                        TempArray33[int(u_xlatu81)].x = u_xlat83;
                        TempArray33[int(u_xlatu84)].x = 1.0;
                    } else {
                        u_xlatu81 = 0u;
                        u_xlatu83 = u_xlatu84;
                        u_xlat85 = 100000.0;
                        while(true){
                            u_xlatb5.x = u_xlatu81>=3u;
                            if(u_xlatb5.x){break;}
                            u_xlat5.x = TempArray37[int(u_xlatu81)].x;
                            u_xlatu32 = u_xlatu81 + 1u;
                            {
                                uint rem = u_xlatu32 % 3u;
                                u_xlatu59 = rem;
                            }
                            u_xlat59 = TempArray37[int(u_xlatu59)].x;
                            u_xlat86 = TempArray36[int(u_xlatu81)].x;
                            u_xlat86 = u_xlat86 * u_xlat86;
                            u_xlat5.x = u_xlat5.x * u_xlat59 + (-u_xlat86);
                            u_xlatb59 = u_xlat85<u_xlat5.x;
                            if(u_xlatb59){
                                u_xlatu83 = u_xlatu81;
                                u_xlat85 = u_xlat5.x;
                            }
                            u_xlatu81 = u_xlatu32;
                        }
                        u_xlatu5.xy = uvec2(u_xlatu83) + uvec2(2u, 1u);
                        {
                            uvec2 rem = u_xlatu5.xy % uvec2(3u, 3u);
                            u_xlatu5.xy = rem;
                        }
                        u_xlat81 = TempArray36[int(u_xlatu5.x)].x;
                        u_xlat84 = TempArray36[int(u_xlatu5.y)].x;
                        u_xlat59 = TempArray37[int(u_xlatu5.y)].x;
                        u_xlat86 = TempArray37[int(u_xlatu83)].x;
                        u_xlat6.x = TempArray36[int(u_xlatu83)].x;
                        u_xlat33 = u_xlat84 * (-u_xlat6.x);
                        u_xlat59 = u_xlat59 * u_xlat81 + u_xlat33;
                        u_xlat84 = u_xlat84 * u_xlat86;
                        u_xlat81 = (-u_xlat6.x) * u_xlat81 + u_xlat84;
                        u_xlat84 = u_xlat59 / u_xlat85;
                        u_xlat81 = u_xlat81 / u_xlat85;
                        TempArray33[int(u_xlatu83)].x = 1.0;
                        TempArray33[int(u_xlatu5.y)].x = 1.0;
                        u_xlat81 = u_xlat81 + u_xlat84;
                        TempArray33[int(u_xlatu5.x)].x = u_xlat81;
                    }
                }
                u_xlat81 = TempArray33[0].x;
                u_xlat83 = TempArray33[1].x;
                u_xlat84 = u_xlat83 * u_xlat83;
                u_xlat84 = u_xlat81 * u_xlat81 + u_xlat84;
                u_xlat85 = TempArray33[2].x;
                u_xlat84 = u_xlat85 * u_xlat85 + u_xlat84;
                u_xlat84 = sqrt(u_xlat84);
                u_xlatb5.x = 0.0<u_xlat84;
                u_xlat81 = u_xlat81 / u_xlat84;
                u_xlat81 = u_xlatb5.x ? u_xlat81 : float(0.0);
                TempArray33[0].x = u_xlat81;
                u_xlat81 = u_xlat83 / u_xlat84;
                u_xlat81 = u_xlatb5.x ? u_xlat81 : float(0.0);
                TempArray33[1].x = u_xlat81;
                u_xlat81 = u_xlat85 / u_xlat84;
                u_xlat81 = u_xlatb5.x ? u_xlat81 : float(0.0);
                TempArray33[2].x = u_xlat81;
            }
            u_xlat81 = TempArray33[0].x;
            u_xlat83 = TempArray33[1].x;
            u_xlat84 = TempArray33[2].x;
            u_xlat5.x = float(0.0);
            u_xlat5.y = float(0.0);
            u_xlat5.z = float(0.0);
            u_xlat6.x = u_xlat81;
            u_xlat6.y = u_xlat83;
            u_xlat6.z = u_xlat84;
            u_xlat8.x = float(0.0);
            u_xlat8.y = float(0.0);
            u_xlat8.z = float(0.0);
            u_xlat9.x = float(0.0);
            u_xlat9.y = float(0.0);
            u_xlat9.z = float(0.0);
            u_xlat85 = 10000000.0;
            while(true){
                for(uint u_xlatu_loop_45 = 0u ; u_xlatu_loop_45<16u ; u_xlatu_loop_45++)
                {
                    TempArray38[int(u_xlatu_loop_45)].x = 0.0;
                    TempArray31[int(u_xlatu_loop_45)].x = 0.0;
                    TempArray30[int(u_xlatu_loop_45)].x = 0.0;
                    TempArray29[int(u_xlatu_loop_45)].x = 0.0;
                }
                u_xlat87 = 1000.0;
                u_xlat61 = -1000.0;
                for(uint u_xlatu_loop_46 = 0u ; u_xlatu_loop_46<u_xlatu27.x ; u_xlatu_loop_46++)
                {
                    u_xlat10.xyz = TempArray35[int(u_xlatu_loop_46)].xyz;
                    u_xlat88 = dot(u_xlat10.xyz, u_xlat6.xyz);
                    TempArray30[int(u_xlatu_loop_46)].x = u_xlat88;
                    TempArray29[int(u_xlatu_loop_46)].x = u_xlat88;
                    u_xlat11.xyz = (-u_xlat6.xyz) * vec3(u_xlat88) + u_xlat10.xyz;
                    u_xlat89 = dot(u_xlat11.xyz, u_xlat11.xyz);
                    TempArray31[int(u_xlatu_loop_46)].x = u_xlat89;
                    u_xlat87 = min(u_xlat87, u_xlat88);
                    u_xlat61 = max(u_xlat88, u_xlat61);
                }
                u_xlat86 = (-u_xlat87) + u_xlat61;
                u_xlat88 = (-u_xlat86) * 0.125 + u_xlat87;
                u_xlat86 = u_xlat86 * 0.125 + u_xlat61;
                u_xlat86 = (-u_xlat88) + u_xlat86;
                u_xlat89 = u_xlat86 * u_xlat86;
                u_xlat90 = float(1.0) / u_xlat86;
                for(uint u_xlatu_loop_47 = 0u ; u_xlatu_loop_47<u_xlatu27.x ; u_xlatu_loop_47++)
                {
                    u_xlat37 = TempArray30[int(u_xlatu_loop_47)].x;
                    u_xlat37 = (-u_xlat88) + u_xlat37;
                    u_xlat37 = u_xlat90 * u_xlat37;
                    TempArray30[int(u_xlatu_loop_47)].x = u_xlat37;
                    u_xlat37 = TempArray28[int(u_xlatu_loop_47)].x;
                    u_xlat37 = u_xlat89 * u_xlat37;
                    TempArray38[int(u_xlatu_loop_47)].x = u_xlat37;
                }
                u_xlat89 = u_xlat87 + (-u_xlat88);
                u_xlat10.x = (-u_xlat88) + u_xlat61;
                u_xlat89 = u_xlat89 * u_xlat90 + -0.0500000007;
                u_xlatb37 = 0.0<u_xlat89;
                u_xlat89 = u_xlatb37 ? u_xlat89 : float(0.0);
                u_xlat90 = u_xlat10.x * u_xlat90 + 0.0500000007;
                u_xlat90 = min(u_xlat90, 1.0);
                u_xlat10.x = TempArray30[0].x;
                u_xlat37 = TempArray30[1].x;
                u_xlat64 = TempArray30[2].x;
                u_xlat91 = TempArray30[3].x;
                u_xlat11.x = TempArray30[4].x;
                u_xlat38 = TempArray30[5].x;
                u_xlat65 = TempArray30[6].x;
                u_xlat92 = TempArray30[7].x;
                u_xlat12.x = TempArray30[8].x;
                u_xlat39 = TempArray30[9].x;
                u_xlat66 = TempArray30[10].x;
                u_xlat93 = TempArray30[11].x;
                u_xlat13 = TempArray30[12].x;
                u_xlat40 = TempArray30[13].x;
                u_xlat67.x = TempArray30[14].x;
                u_xlat94 = TempArray30[15].x;
                u_xlat14.x = TempArray31[0].x;
                u_xlat41.x = TempArray31[1].x;
                u_xlat68 = TempArray31[2].x;
                u_xlat95 = TempArray31[3].x;
                u_xlat15.x = TempArray31[4].x;
                u_xlat42.x = TempArray31[5].x;
                u_xlat69 = TempArray31[6].x;
                u_xlat96 = TempArray31[7].x;
                u_xlat16.x = TempArray31[8].x;
                u_xlat43 = TempArray31[9].x;
                u_xlat70 = TempArray31[10].x;
                u_xlat97 = TempArray31[11].x;
                u_xlat17.x = TempArray31[12].x;
                u_xlat44 = TempArray31[13].x;
                u_xlat71 = TempArray31[14].x;
                u_xlat98 = TempArray31[15].x;
                u_xlat18 = TempArray38[0].x;
                u_xlat45 = TempArray38[1].x;
                u_xlat72 = TempArray38[2].x;
                u_xlat99 = TempArray38[3].x;
                u_xlat19 = TempArray38[4].x;
                u_xlat46 = TempArray38[5].x;
                u_xlat73 = TempArray38[6].x;
                u_xlat100 = TempArray38[7].x;
                u_xlat20 = TempArray38[8].x;
                u_xlat47 = TempArray38[9].x;
                u_xlat74 = TempArray38[10].x;
                u_xlat101 = TempArray38[11].x;
                u_xlat21 = TempArray38[12].x;
                u_xlat48 = TempArray38[13].x;
                u_xlat75 = TempArray38[14].x;
                u_xlat102 = TempArray38[15].x;
                u_xlat22.x = float(0.0);
                u_xlat22.y = float(0.0);
                u_xlat22.z = float(128000.0);
                u_xlat103 = u_xlat89;
                u_xlati23 = 0;
                while(true){
                    u_xlatb50 = u_xlati23>=8;
                    if(u_xlatb50){break;}
                    u_xlat50.xyz = u_xlat22.xyz;
                    u_xlat24 = u_xlat90;
                    u_xlati51 = 0;
                    while(true){
                        u_xlatb78 = u_xlati51>=8;
                        if(u_xlatb78){break;}
                        TempArray39[0].x = u_xlat10.x;
                        TempArray39[1].x = u_xlat37;
                        TempArray39[2].x = u_xlat64;
                        TempArray39[3].x = u_xlat91;
                        TempArray39[4].x = u_xlat11.x;
                        TempArray39[5].x = u_xlat38;
                        TempArray39[6].x = u_xlat65;
                        TempArray39[7].x = u_xlat92;
                        TempArray39[8].x = u_xlat12.x;
                        TempArray39[9].x = u_xlat39;
                        TempArray39[10].x = u_xlat66;
                        TempArray39[11].x = u_xlat93;
                        TempArray39[12].x = u_xlat13;
                        TempArray39[13].x = u_xlat40;
                        TempArray39[14].x = u_xlat67.x;
                        TempArray39[15].x = u_xlat94;
                        TempArray40[0].x = u_xlat14.x;
                        TempArray40[1].x = u_xlat41.x;
                        TempArray40[2].x = u_xlat68;
                        TempArray40[3].x = u_xlat95;
                        TempArray40[4].x = u_xlat15.x;
                        TempArray40[5].x = u_xlat42.x;
                        TempArray40[6].x = u_xlat69;
                        TempArray40[7].x = u_xlat96;
                        TempArray40[8].x = u_xlat16.x;
                        TempArray40[9].x = u_xlat43;
                        TempArray40[10].x = u_xlat70;
                        TempArray40[11].x = u_xlat97;
                        TempArray40[12].x = u_xlat17.x;
                        TempArray40[13].x = u_xlat44;
                        TempArray40[14].x = u_xlat71;
                        TempArray40[15].x = u_xlat98;
                        TempArray41[0].x = u_xlat18;
                        TempArray41[1].x = u_xlat45;
                        TempArray41[2].x = u_xlat72;
                        TempArray41[3].x = u_xlat99;
                        TempArray41[4].x = u_xlat19;
                        TempArray41[5].x = u_xlat46;
                        TempArray41[6].x = u_xlat73;
                        TempArray41[7].x = u_xlat100;
                        TempArray41[8].x = u_xlat20;
                        TempArray41[9].x = u_xlat47;
                        TempArray41[10].x = u_xlat74;
                        TempArray41[11].x = u_xlat101;
                        TempArray41[12].x = u_xlat21;
                        TempArray41[13].x = u_xlat48;
                        TempArray41[14].x = u_xlat75;
                        TempArray41[15].x = u_xlat102;
                        u_xlat78 = (-u_xlat103) + u_xlat24;
                        u_xlat105 = u_xlat78 * 0.333333343;
                        u_xlat25 = float(1.0) / u_xlat105;
                        u_xlat52.x = float(0.0);
                        u_xlat52.y = float(0.0);
                        while(true){
                            u_xlatb106 = floatBitsToUint(u_xlat52.y)>=u_xlatu27.x;
                            if(u_xlatb106){break;}
                            u_xlat106 = TempArray39[floatBitsToInt(u_xlat52.y)].x;
                            u_xlat26 = (-u_xlat103) + u_xlat106;
                            u_xlatb53 = 0.0>=u_xlat26;
                            if(u_xlatb53){
                                u_xlat53 = u_xlat103;
                            } else {
                                u_xlat106 = (-u_xlat24) + u_xlat106;
                                u_xlatb106 = u_xlat106>=0.0;
                                if(u_xlatb106){
                                    u_xlat53 = u_xlat24;
                                } else {
                                    u_xlat106 = u_xlat78 * 0.166666672 + u_xlat26;
                                    u_xlat106 = u_xlat25 * u_xlat106;
                                    u_xlat106 = floor(u_xlat106);
                                    u_xlat53 = u_xlat106 * u_xlat105 + u_xlat103;
                                }
                            }
                            u_xlat106 = TempArray39[floatBitsToInt(u_xlat52.y)].x;
                            u_xlat106 = (-u_xlat53) + u_xlat106;
                            u_xlat106 = u_xlat106 * u_xlat106;
                            u_xlat26 = TempArray41[floatBitsToInt(u_xlat52.y)].x;
                            u_xlat53 = TempArray40[floatBitsToInt(u_xlat52.y)].x;
                            u_xlat106 = u_xlat26 * u_xlat106 + u_xlat53;
                            u_xlat79.y = u_xlat106 + u_xlat52.x;
                            u_xlatb26 = u_xlat50.z<u_xlat79.y;
                            if(u_xlatb26){
                                u_xlat52.x = u_xlat50.z;
                                break;
                            }
                            u_xlat79.x = intBitsToFloat(floatBitsToInt(u_xlat52.y) + 1);
                            u_xlat52.xy = u_xlat79.yx;
                        }
                        u_xlatb78 = u_xlat52.x<u_xlat50.z;
                        if(u_xlatb78){
                            u_xlat50.x = u_xlat103;
                            u_xlat50.y = u_xlat24;
                            u_xlat50.z = u_xlat52.x;
                        }
                        u_xlati51 = u_xlati51 + 1;
                        u_xlat24 = u_xlat24 + -0.0250000004;
                    }
                    u_xlat22.xyz = u_xlat50.xyz;
                    u_xlati23 = u_xlati23 + 1;
                    u_xlat103 = u_xlat103 + 0.0250000004;
                }
                u_xlat89 = u_xlat22.z + 0.00100000005;
                u_xlatb89 = u_xlat89<u_xlat85;
                if(u_xlatb89){
                    u_xlat10.xy = u_xlat22.xy * vec2(u_xlat86) + vec2(u_xlat88);
                    u_xlat86 = (-u_xlat10.x) + u_xlat10.y;
                    u_xlat88 = u_xlat86 * 0.333333343;
                    u_xlat88 = float(1.0) / u_xlat88;
                    for(uint u_xlatu_loop_48 = 0u ; u_xlatu_loop_48<u_xlatu27.x ; u_xlatu_loop_48++)
                    {
                        u_xlat90 = TempArray29[int(u_xlatu_loop_48)].x;
                        u_xlat64 = (-u_xlat10.x) + u_xlat90;
                        u_xlatb91 = 0.0>=u_xlat64;
                        if(u_xlatb91){
                            u_xlat91 = 0.0;
                        } else {
                            u_xlat90 = (-u_xlat10.y) + u_xlat90;
                            u_xlatb90 = u_xlat90>=0.0;
                            if(u_xlatb90){
                                u_xlat91 = 3.0;
                            } else {
                                u_xlat90 = u_xlat86 * 0.166666672 + u_xlat64;
                                u_xlat90 = u_xlat88 * u_xlat90;
                                u_xlat91 = floor(u_xlat90);
                            }
                        }
                        TempArray32[int(u_xlatu_loop_48)].x = u_xlat91;
                        u_xlat90 = u_xlat91 + -1.5;
                        u_xlat90 = u_xlat90 * 0.333333343;
                        TempArray32[int(u_xlatu_loop_48)].x = u_xlat90;
                    }
                    u_xlat11.x = float(0.0);
                    u_xlat11.y = float(0.0);
                    u_xlat11.z = float(0.0);
                    u_xlat88 = 0.0;
                    for(uint u_xlatu_loop_49 = 0u ; u_xlatu_loop_49<u_xlatu27.x ; u_xlatu_loop_49++)
                    {
                        u_xlat89 = TempArray32[int(u_xlatu_loop_49)].x;
                        u_xlat90 = TempArray28[int(u_xlatu_loop_49)].x;
                        u_xlat90 = u_xlat89 * u_xlat90;
                        u_xlat88 = u_xlat89 * u_xlat90 + u_xlat88;
                        u_xlat89 = TempArray35[int(u_xlatu_loop_49)].x;
                        u_xlat11.x = u_xlat89 * u_xlat90 + u_xlat11.x;
                        u_xlat89 = TempArray35[int(u_xlatu_loop_49)].y;
                        u_xlat11.y = u_xlat89 * u_xlat90 + u_xlat11.y;
                        u_xlat89 = TempArray35[int(u_xlatu_loop_49)].z;
                        u_xlat11.z = u_xlat89 * u_xlat90 + u_xlat11.z;
                    }
                    u_xlatb86 = 0.0<u_xlat88;
                    if(u_xlatb86){
                        u_xlat12.xyz = u_xlat11.xyz / vec3(u_xlat88);
                        u_xlat86 = dot(u_xlat12.xyz, u_xlat12.xyz);
                        u_xlat86 = sqrt(u_xlat86);
                        u_xlat12.xyz = u_xlat12.xyz / vec3(u_xlat86);
                    } else {
                        u_xlat12.x = float(0.0);
                        u_xlat12.y = float(0.0);
                        u_xlat12.z = float(0.0);
                    }
                    u_xlat8.xyz = u_xlat10.xxx;
                    u_xlat9.xyz = u_xlat10.yyy;
                } else {
                    break;
                }
                u_xlat5.xyz = u_xlat6.xyz;
                u_xlat6.xyz = u_xlat12.xyz;
                u_xlat85 = u_xlat22.z;
            }
            u_xlat6.xyz = u_xlat8.xyz * u_xlat5.xyz + u_xlat4.xyz;
            u_xlat6.xyz = u_xlat6.xyz * vec3(255.0, 255.0, 255.0);
            u_xlat4.xyz = u_xlat9.xyz * u_xlat5.xyz + u_xlat4.xyz;
            u_xlat4.xyz = u_xlat4.xyz * vec3(255.0, 255.0, 255.0);
            u_xlat5.xyz = floor(u_xlat6.xyz);
            u_xlatb6.xyz = greaterThanEqual(vec4(0.0, 0.0, 0.0, 0.0), u_xlat5.xyzx).xyz;
            if(u_xlatb6.x){
                u_xlat8.x = 0.0;
            } else {
                u_xlat27.x = u_xlat5.x * 0.03125;
                u_xlat27.x = floor(u_xlat27.x);
                u_xlat27.x = (-u_xlat27.x) + u_xlat5.x;
                u_xlat27.x = u_xlat27.x + 4.0;
                u_xlat8.x = min(u_xlat27.x, 255.0);
            }
            if(u_xlatb6.y){
                u_xlat8.y = 0.0;
            } else {
                u_xlat27.x = u_xlat5.y * 0.015625;
                u_xlat27.x = floor(u_xlat27.x);
                u_xlat27.x = (-u_xlat27.x) + u_xlat5.y;
                u_xlat27.x = u_xlat27.x + 2.0;
                u_xlat8.y = min(u_xlat27.x, 255.0);
            }
            if(u_xlatb6.z){
                u_xlat8.z = 0.0;
            } else {
                u_xlat27.x = u_xlat5.z * 0.03125;
                u_xlat27.x = floor(u_xlat27.x);
                u_xlat27.x = (-u_xlat27.x) + u_xlat5.z;
                u_xlat27.x = u_xlat27.x + 4.0;
                u_xlat8.z = min(u_xlat27.x, 255.0);
            }
            u_xlat5.xyz = u_xlat8.xyz * vec3(0.125, 0.25, 0.125);
            u_xlat5.xyz = floor(u_xlat5.xyz);
            u_xlat2.xyz = u_xlat5.xyz * vec3(8.0, 4.0, 8.0);
            u_xlat4.xyz = floor(u_xlat4.xyz);
            u_xlatb5.xyz = greaterThanEqual(vec4(0.0, 0.0, 0.0, 0.0), u_xlat4.xyzx).xyz;
            if(u_xlatb5.x){
                u_xlat6.x = 0.0;
            } else {
                u_xlat27.x = u_xlat4.x * 0.03125;
                u_xlat27.x = floor(u_xlat27.x);
                u_xlat27.x = (-u_xlat27.x) + u_xlat4.x;
                u_xlat27.x = u_xlat27.x + 4.0;
                u_xlat6.x = min(u_xlat27.x, 255.0);
            }
            if(u_xlatb5.y){
                u_xlat6.y = 0.0;
            } else {
                u_xlat27.x = u_xlat4.y * 0.015625;
                u_xlat27.x = floor(u_xlat27.x);
                u_xlat27.x = (-u_xlat27.x) + u_xlat4.y;
                u_xlat27.x = u_xlat27.x + 2.0;
                u_xlat6.y = min(u_xlat27.x, 255.0);
            }
            if(u_xlatb5.z){
                u_xlat6.z = 0.0;
            } else {
                u_xlat27.x = u_xlat4.z * 0.03125;
                u_xlat27.x = floor(u_xlat27.x);
                u_xlat27.x = (-u_xlat27.x) + u_xlat4.z;
                u_xlat27.x = u_xlat27.x + 4.0;
                u_xlat6.z = min(u_xlat27.x, 255.0);
            }
            u_xlat4.xyz = u_xlat6.xyz * vec3(0.125, 0.25, 0.125);
            u_xlat4.xyz = floor(u_xlat4.xyz);
            u_xlat3.xyz = u_xlat4.xyz * vec3(8.0, 4.0, 8.0);
        }
        if(u_xlati54 == 0) {
            u_xlatu4.xyz = uvec3(u_xlat2.zyx);
            u_xlati27.xz = ivec2(u_xlatu4.xy) << (ivec2(8, 3) & int(0x1F));
            u_xlati27.xz = ivec2(uvec2(u_xlati27.xz) & uvec2(63488u, 2016u));
            u_xlati83 = u_xlati27.x + u_xlati27.z;
            u_xlatu84 = uint(uint_bitfieldExtract(u_xlatu4.z, 3 & int(0x1F), 5 & int(0x1F)));
            u_xlatu83 = uint(u_xlati83) + u_xlatu84;
            u_xlatu4.xyz = uvec3(u_xlat3.zyx);
            u_xlati4.xy = ivec2(u_xlatu4.xy) << (ivec2(8, 3) & int(0x1F));
            u_xlati4.xy = ivec2(uvec2(u_xlati4.xy) & uvec2(63488u, 2016u));
            u_xlati85 = u_xlati4.x + u_xlati4.y;
            u_xlatu58 = uint(uint_bitfieldExtract(u_xlatu4.z, 3 & int(0x1F), 5 & int(0x1F)));
            u_xlatu85 = uint(u_xlati85) + u_xlatu58;
            u_xlatb5.x = int(u_xlatu85)>=int(u_xlatu83);
            if(u_xlatb5.x){
                u_xlat5.xyz = u_xlat2.xyz;
                u_xlat2.xyz = u_xlat3.xyz;
            } else {
                u_xlat5.xyz = u_xlat3.xyz;
            }
            for(uint u_xlatu_loop_50 = 0u ; u_xlatu_loop_50<16u ; u_xlatu_loop_50++)
            {
                u_xlat30 = TempArray22[int(u_xlatu_loop_50)].x;
                u_xlat30 = u_xlat30 * 255.0;
                TempArray42[int(u_xlatu_loop_50)].z = u_xlat30;
                u_xlat30 = TempArray22[int(u_xlatu_loop_50)].y;
                u_xlat30 = u_xlat30 * 255.0;
                TempArray42[int(u_xlatu_loop_50)].y = u_xlat30;
                u_xlat30 = TempArray22[int(u_xlatu_loop_50)].z;
                u_xlat30 = u_xlat30 * 255.0;
                TempArray42[int(u_xlatu_loop_50)].x = u_xlat30;
            }
            u_xlat3.xyz = u_xlat2.xyz * vec3(0.03125, 0.015625, 0.03125);
            u_xlat3.xyz = floor(u_xlat3.xyz);
            u_xlat2.xyz = u_xlat2.xyz + u_xlat3.xyz;
            u_xlat2.xyz = max(u_xlat2.xyz, vec3(0.0, 0.0, 0.0));
            u_xlat2.xyz = min(u_xlat2.xyz, vec3(255.0, 255.0, 255.0));
            u_xlat3.xyz = u_xlat5.xyz * vec3(0.03125, 0.015625, 0.03125);
            u_xlat3.xyz = floor(u_xlat3.xyz);
            u_xlat3.xyz = u_xlat3.xyz + u_xlat5.xyz;
            u_xlat3.xyz = max(u_xlat3.xyz, vec3(0.0, 0.0, 0.0));
            u_xlat3.xyz = min(u_xlat3.xyz, vec3(255.0, 255.0, 255.0));
            TempArray43[0].xyz = u_xlat2.xyz;
            TempArray43[3].xyz = u_xlat3.xyz;
            u_xlat5.xyz = u_xlat2.xyz * vec3(2.0, 2.0, 2.0) + u_xlat3.xyz;
            u_xlat5.xyz = u_xlat5.xyz + vec3(1.0, 1.0, 1.0);
            u_xlat5.xyz = u_xlat5.xyz * vec3(0.333333343, 0.333333343, 0.333333343);
            u_xlat5.xyz = floor(u_xlat5.xyz);
            TempArray43[1].xyz = u_xlat5.xyz;
            u_xlat2.xyz = u_xlat3.xyz * vec3(2.0, 2.0, 2.0) + u_xlat2.xyz;
            u_xlat2.xyz = u_xlat2.xyz + vec3(1.0, 1.0, 1.0);
            u_xlat2.xyz = u_xlat2.xyz * vec3(0.333333343, 0.333333343, 0.333333343);
            u_xlat2.xyz = floor(u_xlat2.xyz);
            TempArray43[2].xyz = u_xlat2.xyz;
            u_xlat3.y = 0.0;
            for(uint u_xlatu_loop_51 = 0u ; u_xlatu_loop_51<16u ; u_xlatu_loop_51++)
            {
                u_xlat5.xyz = TempArray42[int(u_xlatu_loop_51)].xyz;
                u_xlatu6 = uint(0u);
                u_xlat33 = float(9.9999998e+10);
                for(uint u_xlatu_loop_52 = uint(0u) ; u_xlatu_loop_52<4u ; u_xlatu_loop_52++)
                {
                    u_xlat8.xyz = TempArray43[int(u_xlatu_loop_52)].xyz;
                    u_xlat8.xyz = u_xlat5.xyz + (-u_xlat8.xyz);
                    u_xlat29 = dot(u_xlat8.xyz, u_xlat8.xyz);
                    u_xlatb56 = u_xlat29<u_xlat33;
                    if(u_xlatb56){
                        u_xlat33 = u_xlat29;
                        u_xlatu6 = u_xlatu_loop_52;
                    }
                }
                u_xlatb29 = int(u_xlatu6)==3;
                if(u_xlatb29){
                    u_xlati29 = 1;
                } else {
                    if(u_xlatu6 != uint(0)) {
                        u_xlati29 = int(u_xlatu6) + 1;
                    } else {
                        u_xlati29 = 0;
                    }
                }
                u_xlati56 = int(u_xlatu_loop_51) << (1 & int(0x1F));
                u_xlati29 = int(bitfieldInsert(0, u_xlati29, u_xlati56 & int(0x1F), 2));
                u_xlat3.y = uintBitsToFloat(uint(u_xlati29) | floatBitsToUint(u_xlat3.y));
            }
            u_xlatb2 = u_xlatu85>=u_xlatu83;
            if(u_xlatb2){
                u_xlati27.x = u_xlati27.x << (16 & int(0x1F));
                u_xlati27.x = u_xlati27.z * 65536 + u_xlati27.x;
                u_xlati27.x = int(u_xlatu84) * 65536 + u_xlati27.x;
                u_xlat3.x = intBitsToFloat(u_xlati27.x + int(u_xlatu85));
            } else {
                u_xlati27.x = u_xlati4.x << (16 & int(0x1F));
                u_xlati27.x = u_xlati4.y * 65536 + u_xlati27.x;
                u_xlati27.x = int(u_xlatu58) * 65536 + u_xlati27.x;
                u_xlat3.x = intBitsToFloat(u_xlati27.x + int(u_xlatu83));
            }
            u_xlatu27.x = floatBitsToUint(u_xlat3.x) & 65535u;
            u_xlatu81 = floatBitsToUint(u_xlat3.x) >> (16u & uint(0x1F));
            u_xlatu2 = uvec4(uint_bitfieldExtract(floatBitsToUint(u_xlat3.x), int(8) & int(0x1F), int(8) & int(0x1F)), uint_bitfieldExtract(floatBitsToUint(u_xlat3.x), int(3) & int(0x1F), int(13) & int(0x1F)), uint_bitfieldExtract(floatBitsToUint(u_xlat3.x), int(13) & int(0x1F), int(3) & int(0x1F)), uint_bitfieldExtract(floatBitsToUint(u_xlat3.x), int(25) & int(0x1F), int(2) & int(0x1F)));
            u_xlatu57.x = u_xlatu2.y & 252u;
            u_xlatu57.y =  uint(int(bitfieldInsert(0, floatBitsToInt(u_xlat3.x), 3 & int(0x1F), 5)));
            u_xlatu2.x =  uint(int(bitfieldInsert(int(u_xlatu2.x), int(u_xlatu2.z), 0 & int(0x1F), 3)));
            u_xlatu29.x = uint(uint_bitfieldExtract(u_xlatu2.y, 6 & int(0x1F), 2 & int(0x1F)));
            u_xlatu29.y = u_xlatu57.y >> (5u & uint(0x1F));
            u_xlatu2.yz = u_xlatu57.xy + u_xlatu29.xy;
            u_xlat4.xyz = vec3(u_xlatu2.xyz);
            u_xlatu2.xy = uvec2(u_xlatu81) >> (uvec2(8u, 3u) & uint(0x1F));
            u_xlati29 = int(u_xlatu2.y & 252u);
            u_xlatu56.x =  uint(int(bitfieldInsert(0, int(u_xlatu81), 3 & int(0x1F), 5)));
            u_xlatu57.x = u_xlatu2.x >> (5u & uint(0x1F));
            u_xlatu2.x =  uint(int(bitfieldInsert(int(u_xlatu2.x), int(u_xlatu57.x), 0 & int(0x1F), 3)));
            u_xlatu2.y = uint(u_xlati29) + u_xlatu2.w;
            u_xlatu83 = u_xlatu56.x >> (5u & uint(0x1F));
            u_xlatu2.z = u_xlatu56.x + u_xlatu83;
            u_xlat5.xyz = vec3(u_xlatu2.xyz);
            u_xlatb27 = u_xlatu81<u_xlatu27.x;
            if(u_xlatb27){
                u_xlat2.xyz = u_xlat4.xyz * vec3(2.0, 2.0, 2.0) + u_xlat5.xyz;
                u_xlat2.xyz = u_xlat2.xyz * vec3(0.333333343, 0.333333343, 0.333333343);
                u_xlat6.xyz = u_xlat5.xyz * vec3(2.0, 2.0, 2.0) + u_xlat4.xyz;
                u_xlat6.xyz = u_xlat6.xyz * vec3(0.333333343, 0.333333343, 0.333333343);
                for(uint u_xlatu_loop_53 = 0u ; u_xlatu_loop_53<16u ; u_xlatu_loop_53++)
                {
                    u_xlatu81 =  uint(int(u_xlatu_loop_53) << (1 & int(0x1F)));
                    u_xlatu81 = floatBitsToUint(u_xlat3.y) >> (u_xlatu81 & uint(0x1F));
                    u_xlati81 = int(u_xlatu81 & 3u);
                    switch(u_xlati81){
                        case 0:
                            TempArray44[int(u_xlatu_loop_53)].xyz = u_xlat4.xyz;
                            break;
                        case 1:
                            TempArray44[int(u_xlatu_loop_53)].xyz = u_xlat5.xyz;
                            break;
                        case 2:
                            TempArray44[int(u_xlatu_loop_53)].xyz = u_xlat2.xyz;
                            break;
                        case 3:
                            TempArray44[int(u_xlatu_loop_53)].xyz = u_xlat6.xyz;
                            break;
                    }
                }
            } else {
                u_xlat2.xyz = u_xlat4.xyz + u_xlat5.xyz;
                u_xlat2.xyz = u_xlat2.xyz * vec3(0.5, 0.5, 0.5);
                for(uint u_xlatu_loop_54 = 0u ; u_xlatu_loop_54<16u ; u_xlatu_loop_54++)
                {
                    u_xlatu81 =  uint(int(u_xlatu_loop_54) << (1 & int(0x1F)));
                    u_xlatu81 = floatBitsToUint(u_xlat3.y) >> (u_xlatu81 & uint(0x1F));
                    u_xlati81 = int(u_xlatu81 & 3u);
                    switch(u_xlati81){
                        case 0:
                            TempArray44[int(u_xlatu_loop_54)].xyz = u_xlat4.xyz;
                            break;
                        case 1:
                            TempArray44[int(u_xlatu_loop_54)].xyz = u_xlat5.xyz;
                            break;
                        case 2:
                            TempArray44[int(u_xlatu_loop_54)].xyz = u_xlat2.xyz;
                            break;
                        case 3:
                            TempArray44[int(u_xlatu_loop_54)].xyz = vec3(0.0, 0.0, 0.0);
                            break;
                    }
                }
            }
            u_xlat2.x = float(0.0);
            u_xlat29 = float(0.0);
            u_xlat56.x = float(0.0);
            for(int u_xlati_loop_55 = 0 ; u_xlati_loop_55<16 ; u_xlati_loop_55++)
            {
                u_xlat81 = TempArray16[u_xlati_loop_55].x;
                u_xlat81 = u_xlat81 * 255.0;
                u_xlat81 = roundEven(u_xlat81);
                u_xlat83 = TempArray16[u_xlati_loop_55].y;
                u_xlat83 = u_xlat83 * 255.0;
                u_xlat83 = roundEven(u_xlat83);
                u_xlat57 = TempArray16[u_xlati_loop_55].z;
                u_xlat57 = u_xlat57 * 255.0;
                u_xlat57 = roundEven(u_xlat57);
                u_xlat84 = TempArray44[u_xlati_loop_55].x;
                u_xlat4.x = TempArray44[u_xlati_loop_55].y;
                u_xlat31.x = TempArray44[u_xlati_loop_55].z;
                u_xlat81 = u_xlat81 + (-u_xlat84);
                u_xlat2.x = u_xlat81 * u_xlat81 + u_xlat2.x;
                u_xlat81 = u_xlat83 + (-u_xlat4.x);
                u_xlat29 = u_xlat81 * u_xlat81 + u_xlat29;
                u_xlat81 = u_xlat57 + (-u_xlat31.x);
                u_xlat56.x = u_xlat81 * u_xlat81 + u_xlat56.x;
            }
            u_xlat27.x = u_xlat29 + u_xlat2.x;
            u_xlat27.x = u_xlat56.x + u_xlat27.x;
            u_xlat27.x = u_xlat27.x * 0.020833334;
            u_xlatb0 = u_xlat0.x<u_xlat27.x;
            if(!u_xlatb0){
                u_xlat7.xy = u_xlat3.xy;
            }
        }
    } else {
        u_xlati54 = 0;
    }
    if(u_xlati54 == 0) {
        u_xlat1.zw = u_xlat7.xy;
    }
    imageStore(_Target, ivec2(gl_GlobalInvocationID.xy), floatBitsToUint(u_xlat1));
    return;
}


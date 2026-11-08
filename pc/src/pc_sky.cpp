/* A direction-only sky: no eye translation, finite dome, or stereo disparity.
 * Procedural rounded clouds keep the low-detail, painted look of the village.
 * Embedded shaders keep the executable self-contained and rollback simple. */
#include "pc_sky.h"
#include "pc_settings.h"
#include "pc_gl.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <stdint.h>
#include <stdlib.h>

extern "C" { extern uint32_t pc_frame_counter; extern int g_pc_paused; }

static GLuint program, vao;
static int failed, outdoors, have_environment, have_view, drawn, environment_active;
static uint32_t environment_frame;
static float seconds, overcast, view[12];
static GLint u_projection, u_rotation, u_environment;
static GLint u_acww_sky, u_acww_clouds, u_acww_stars, u_acww_moon, u_acww_assets;
static GLuint acww_sky_texture, acww_cloud_texture, acww_star_texture, acww_moon_texture;
static int acww_assets_checked, acww_assets_ready, acww_cloud_variant = -1, acww_palette_bank = -1;
static unsigned char *acww_cloud_ncg, *acww_cloud_f0_map, *acww_cloud_c0_map, *acww_cloud_r0_map;
static unsigned char *acww_cloud_f0_palette, *acww_cloud_r0_palette;

static const char* vertex_source = PC_GLSL_HEADER R"GLSL(
out vec2 screen;
void main() {
    screen = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2) * 2.0 - 1.0;
    gl_Position = vec4(screen, 1.0, 1.0);
}
)GLSL";

static const char* fragment_source = PC_GLSL_HEADER R"GLSL(
in vec2 screen;
out vec4 color;
uniform vec4 projection; // P00, P11, P02, P12 (asymmetric per-eye frusta)
uniform mat3 eye_to_world;
uniform vec2 environment; // game seconds, overcast blend
uniform sampler2D acww_sky_layer;
uniform sampler2D acww_cloud_layer;
uniform sampler2D acww_star_layer;
uniform sampler2D acww_moon_layer;
uniform int acww_assets;
const float PI = 3.14159265359;
float hash(float n) { return fract(sin(n * 127.1 + 311.7) * 43758.5453); }
float oval(vec2 p, vec2 center, vec2 size) {
    return length((p-center)/size);
}
// Wild World-inspired cloud banks: flatter, longer, soft-edged layers.
float cloud(vec2 p, float seed) {
    // Train-ride-inspired cloud banks: a calm flat underside with distinct,
    // rounded upper puffs. Keep the silhouette soft without blurring it away.
    p.x += 0.008*sin(p.y*4.0+seed*8.0);
    p.y += 0.006*sin(p.x*6.0+seed*13.0);
    float d = oval(p, vec2(0.0,-0.12), vec2(0.86,0.17));
    d = min(d, oval(p, vec2(-0.58,-0.005), vec2(0.31,0.19)));
    d = min(d, oval(p, vec2(-0.30,0.105), vec2(0.30,0.27)));
    d = min(d, oval(p, vec2(0.02,0.16+0.025*seed), vec2(0.34,0.31)));
    d = min(d, oval(p, vec2(0.37,0.105), vec2(0.32,0.25)));
    d = min(d, oval(p, vec2(0.66,0.005), vec2(0.31,0.18)));
    return 1.0-smoothstep(0.91,1.035,d);
}
void main() {
    vec3 eye = vec3((screen + projection.zw)/projection.xy, -1.0);
    vec3 ray = normalize(eye_to_world * eye);
    float h = ray.y;
    float azimuth = atan(ray.x,ray.z)/(2.0*PI)+0.5;
    float hour = environment.x/3600.0;
    float wet = clamp(environment.y,0.0,1.0);
    float day = smoothstep(5.0,7.5,hour)*(1.0-smoothstep(17.0,20.0,hour));
    float dusk = max(1.0-abs(hour-6.0)/1.5,1.0-abs(hour-18.5)/1.5);
    dusk = max(dusk,0.0)*(1.0-wet);
    // Bright daytime blue with a peach-to-lavender twilight transition.
    vec3 zenith = mix(vec3(0.012,0.020,0.070),vec3(0.055,0.34,0.91),day);
    vec3 horizon = mix(vec3(0.055,0.075,0.16),vec3(0.72,0.87,0.98),day);
    vec3 duskZenith = vec3(0.30,0.24,0.43);
    vec3 duskHorizon = vec3(1.00,0.62,0.48);
    zenith = mix(zenith,duskZenith,dusk*0.72);
    horizon = mix(horizon,duskHorizon,dusk*0.86);
    zenith = mix(zenith,mix(vec3(0.035,0.045,0.075),vec3(0.39,0.47,0.56),day),wet);
    horizon = mix(horizon,mix(vec3(0.085,0.10,0.15),vec3(0.68,0.73,0.78),day),wet);
    vec3 sky = mix(horizon,zenith,pow(clamp(h,0.0,1.0),0.48));
    // Fade below the horizon to a quiet blue haze behind the actual terrain.
    sky = mix(sky,horizon*mix(0.55,0.77,day),smoothstep(0.0,0.20,-h));

    // Procedural night details; no Wild World textures or ROM assets are used.
    float night = max(1.0-smoothstep(5.0,7.0,hour),
                      smoothstep(18.0,20.5,hour));
    if (h > 0.10 && night > 0.001) {
        if (acww_assets != 0) {
            vec4 stars = texture(acww_star_layer,
                vec2(fract(azimuth), 1.0-clamp((h-0.08)/0.88,0.0,1.0)));
            sky = mix(sky,stars.rgb,stars.a*night);
        } else {
            vec2 starCell = vec2(azimuth*420.0,(h+0.15)*190.0);
            vec2 starId = floor(starCell);
            vec2 starLocal = fract(starCell)-0.5;
            float starSeed = hash(starId.x+starId.y*733.0);
            float starSize = mix(0.010,0.026,hash(starId.x*3.1+starId.y*17.7));
            float star = (1.0-smoothstep(starSize,starSize+0.012,length(starLocal)))
                       * step(0.986,starSeed);
            sky += vec3(0.72,0.82,1.0)*star*night*0.72;
        }
        float moonAzimuth = fract(0.72+environment.x/86400.0);
        float moonDelta = azimuth-moonAzimuth;
        if (moonDelta>0.5) moonDelta-=1.0;
        if (moonDelta< -0.5) moonDelta+=1.0;
        vec2 moonPoint = vec2(moonDelta,h-0.43);
        if (acww_assets != 0) {
            if (abs(moonPoint.x)<0.04 && abs(moonPoint.y)<0.04) {
                vec2 moonUV = vec2(moonPoint.x/0.08+0.5,0.5-moonPoint.y/0.08);
                vec4 moonSprite = texture(acww_moon_layer,moonUV);
                sky = mix(sky,moonSprite.rgb,moonSprite.a*night*0.96);
            }
        } else {
            float moon = 1.0-smoothstep(0.035,0.043,length(moonPoint));
            sky = mix(sky,vec3(0.96,0.94,0.82),moon*night*0.92);
            sky += vec3(0.30,0.40,0.72)*exp(-length(moonPoint)*18.0)*0.10*night;
        }
    }

    // Sparse distant island silhouettes, entirely behind the playable world.
    float ridge = 0.006 + 0.015*pow(max(0.0,sin(azimuth*2.0*PI*7.0+1.2)),4.0)
                        + 0.009*pow(max(0.0,sin(azimuth*2.0*PI*19.0)),6.0);
    float islands = smoothstep(0.52,0.75,sin(azimuth*2.0*PI*5.0));
    float land = (1.0-smoothstep(ridge-0.002,ridge+0.002,h))*smoothstep(-0.012,0.0,h)*islands;
    sky = mix(sky,horizon*0.76,land*(1.0-wet*0.5));

    // The imported DS cloud map is retained for future palette reconstruction,
    // but its current tile decode produces smeared patches in the Quest view.
    // Use the stable rounded cloud banks for every sky mode; imported stars and
    // the moon remain enabled above.

    // Periodic longitude, bounded drift (same time for both eyes), no cube seams.
    if (h > 0.015 && h < 0.87) {
        float latitude = asin(h);
        for (int row=0; row<3; ++row) {
            float r = float(row);
            // Cloud row bounds: no candidate can pass abs(p.y)<1.2 outside
            // this latitude range; the extra margin protects rounding edges.
            float low = 0.055+r*0.22;
            float high = low+0.045+r*0.11;
            float extent = 1.2*(0.025+r*0.065)+0.00001;
            if (latitude<low-extent || latitude>high+extent) continue;
            float count = 18.0-r*5.0;
            // One full revolution per game day joins continuously at midnight.
            float x = (azimuth+environment.x/86400.0)*count + r*2.71;
            float cell = floor(x);
            for (int neighbor=-1; neighbor<=1; ++neighbor) {
                float id = mod(cell+float(neighbor),count);
                float seed = hash(id+r*39.0);
                float center = 0.5+(seed-0.5)*0.32;
                float elevation = 0.055+r*0.22+hash(id+93.0+r*17.0)*(0.045+r*0.11);
                vec2 p = vec2((x-cell-float(neighbor)-center)/(0.22+seed*0.14),
                              (latitude-elevation)/(0.025+r*0.065));
                if (abs(p.x)<1.4 && abs(p.y)<1.2 && (row==0 || seed>0.15)) {
                    float cover = cloud(p,seed)*mix(0.78,0.96,r/2.0);
                    vec3 shade = mix(vec3(0.12,0.14,0.25),vec3(0.63,0.75,0.91),day);
                    vec3 lit = mix(vec3(0.26,0.27,0.39),vec3(0.99,0.99,0.97),day);
                    lit = mix(lit,vec3(1.00,0.70,0.59),dusk*0.72);
                    lit = mix(lit,vec3(0.58,0.56,0.73),(1.0-day)*0.25);
                    vec3 puff = mix(shade,lit,smoothstep(-0.16,0.42,p.y));
                    puff = mix(puff,horizon*0.94,wet*0.85);
                    sky = mix(sky,puff,cover);
                }
            }
        }
    }
    /* Clouds use the rounded procedural layer until the imported NDS cloud
     * tile attributes and palette timing are fully reconstructed. */
    color = vec4(sky,1.0);
}
)GLSL";

static GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader,1,&source,NULL);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
    if (!ok) {
        char log[2048]; glGetShaderInfoLog(shader,sizeof(log),NULL,log);
        fprintf(stderr,"[Sky] Shader failed: %s\n",log);
        glDeleteShader(shader); return 0;
    }
    return shader;
}

static int init(void) {
    if (program) return 1;
    if (failed) return 0;
    failed = 1; // A failure falls back to the stock clear color; never spam retries.
    GLuint vs = compile(GL_VERTEX_SHADER,vertex_source);
    GLuint fs = compile(GL_FRAGMENT_SHADER,fragment_source);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return 0;
    }
    GLuint candidate = glCreateProgram();
    glAttachShader(candidate,vs); glAttachShader(candidate,fs); glLinkProgram(candidate);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint ok = 0; glGetProgramiv(candidate,GL_LINK_STATUS,&ok);
    if (!ok) {
        char log[2048]; glGetProgramInfoLog(candidate,sizeof(log),NULL,log);
        fprintf(stderr,"[Sky] Link failed: %s\n",log);
        glDeleteProgram(candidate); return 0;
    }
    program = candidate;
    glGenVertexArrays(1,&vao);
    u_projection = glGetUniformLocation(program,"projection");
    u_rotation = glGetUniformLocation(program,"eye_to_world");
    u_environment = glGetUniformLocation(program,"environment");
    u_acww_sky = glGetUniformLocation(program,"acww_sky_layer");
    u_acww_clouds = glGetUniformLocation(program,"acww_cloud_layer");
    u_acww_stars = glGetUniformLocation(program,"acww_star_layer");
    u_acww_moon = glGetUniformLocation(program,"acww_moon_layer");
    u_acww_assets = glGetUniformLocation(program,"acww_assets");
    fprintf(stdout,"[Sky] Outdoor sky renderer initialized\n");
    return 1;
}

static unsigned char* read_asset(const char* path, size_t expected, size_t* out_size) {
    FILE* file = fopen(path,"rb");
    if (!file) return NULL;
    if (fseek(file,0,SEEK_END)!=0) { fclose(file); return NULL; }
    long length = ftell(file);
    if (length < 0 || (expected && (size_t)length != expected) || length > 2*1024*1024) {
        fclose(file); return NULL;
    }
    rewind(file);
    unsigned char* data = (unsigned char*)malloc((size_t)length);
    if (!data || fread(data,1,(size_t)length,file)!=(size_t)length) {
        free(data); fclose(file); return NULL;
    }
    fclose(file);
    if (out_size) *out_size=(size_t)length;
    return data;
}

static void nds_color_rgba(const unsigned char* palette, int index, unsigned char* out) {
    unsigned value=(unsigned)palette[index*2] | ((unsigned)palette[index*2+1]<<8);
    out[0]=(unsigned char)(((value&31u)*255u)/31u);
    out[1]=(unsigned char)((((value>>5)&31u)*255u)/31u);
    out[2]=(unsigned char)((((value>>10)&31u)*255u)/31u);
    out[3]=255;
}

static unsigned char* decode_sky_strip(const unsigned char* ncg,const unsigned char* ncl,
                                       const unsigned char* nsc) {
    const int width=512,height=128;
    unsigned char* rgba=(unsigned char*)calloc((size_t)width*height,4);
    if (!rgba) return NULL;
    for (int ty=0;ty<16;ty++) for (int tx=0;tx<64;tx++) {
        int tile=nsc[ty*64+tx];
        if (tile>=512) continue;
        const unsigned char* src=ncg+tile*32;
        for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
            unsigned char packed=src[y*4+x/2];
            int color=(x&1)?(packed>>4):(packed&15);
            if (!color) continue;
            unsigned char* dst=rgba+(((ty*8+y)*width)+(tx*8+x))*4;
            nds_color_rgba(ncl,color,dst);
        }
    }
    return rgba;
}

static unsigned char* decode_star_map(const unsigned char* ncg,const unsigned char* ncl,
                                      const unsigned char* nsc) {
    const int width=256,height=256;
    unsigned char* rgba=(unsigned char*)calloc((size_t)width*height,4);
    if (!rgba) return NULL;
    for (int ty=0;ty<32;ty++) for (int tx=0;tx<32;tx++) {
        int entry=(int)nsc[(ty*32+tx)*2] | ((int)nsc[(ty*32+tx)*2+1]<<8);
        int tile=(entry&255)-16;
        if (tile<0 || tile>=16) continue;
        const unsigned char* src=ncg+tile*32;
        for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
            unsigned char packed=src[y*4+x/2];
            int color=(x&1)?(packed>>4):(packed&15);
            if (!color) continue;
            unsigned char* dst=rgba+(((ty*8+y)*width)+(tx*8+x))*4;
            nds_color_rgba(ncl,color,dst);
        }
    }
    return rgba;
}

static unsigned char* decode_moon_sprite(const unsigned char* ncg,const unsigned char* ncl) {
    const int width=32,height=32;
    unsigned char* rgba=(unsigned char*)calloc((size_t)width*height,4);
    if (!rgba) return NULL;
    /* The Wild World sky object atlas stores its 32x32 full-moon cel at
     * tile columns 28-31, rows 20-23. Palette zero is the moon-object palette. */
    for (int ty=0;ty<4;ty++) for (int tx=0;tx<4;tx++) {
        int tile=(20+ty)*32+(28+tx);
        const unsigned char* src=ncg+tile*32;
        for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
            unsigned char packed=src[y*4+x/2];
            int color=(x&1)?(packed>>4):(packed&15);
            if (!color) continue;
            unsigned char* dst=rgba+(((ty*8+y)*width)+(tx*8+x))*4;
            nds_color_rgba(ncl,color,dst);
        }
    }
    return rgba;
}

static unsigned char* decode_cloud_map(const unsigned char* ncg,const unsigned char* ncl,
                                       const unsigned char* nsc,int palette_bank) {
    /* NDS cloud map is 32 tiles wide by 16 high: 1024 byte tile indices.
     * Each 8bpp tile is 8x8 bytes, so the decoded layer is 256x128. */
    const int width=256,height=128;
    unsigned char* rgba=(unsigned char*)calloc((size_t)width*height,4);
    if (!rgba) return NULL;
    for (int ty=0;ty<16;ty++) for (int tx=0;tx<32;tx++) {
        int tile=(int)nsc[ty*32+tx]-16;
        if (tile<0 || tile>=32) continue;
        const unsigned char* src=ncg+tile*64;
        for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
            int color=src[y*8+x]&15;
            if (!color) continue;
            int palette_index=palette_bank*16+color;
            if (palette_index>=192) continue;
            unsigned char* dst=rgba+(((ty*8+y)*width)+(tx*8+x))*4;
            nds_color_rgba(ncl,palette_index,dst);
        }
    }
    return rgba;
}

static GLuint upload_rgba_texture(int width,int height,const unsigned char* pixels) {
    GLint previous_binding=0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D,&previous_binding);
    GLuint texture=0;
    glGenTextures(1,&texture);
    if (!texture) return 0;
    glBindTexture(GL_TEXTURE_2D,texture);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    glBindTexture(GL_TEXTURE_2D,(GLuint)previous_binding);
    return texture;
}

static int load_wild_world_assets(void) {
    unsigned char* sky_ncg=read_asset("acww_sky/sky/d_2d_sky00_ncg.bin",16384,NULL);
    unsigned char* sky_ncl=read_asset("acww_sky/sky/d_2d_sky00_ncl.bin",32,NULL);
    unsigned char* sky_nsc=read_asset("acww_sky/sky/d_2d_sky00_nsc.bin",1024,NULL);
    unsigned char* star_ncg=read_asset("acww_sky/sky/d_2d_star_ncg.bin",512,NULL);
    unsigned char* star_ncl=read_asset("acww_sky/sky/d_2d_star_ncl.bin",32,NULL);
    unsigned char* star_nsc=read_asset("acww_sky/sky/d_2d_star_nsc.bin",2048,NULL);
    unsigned char* moon_ncg=read_asset("acww_sky/sky/a_sky_obj_ncg.bin",32768,NULL);
    unsigned char* moon_ncl=read_asset("acww_sky/sky/a_sky_moon_obj_ncl.bin",320,NULL);
    acww_cloud_ncg=read_asset("acww_sky/sky/d_2d_b_cld_bg_ncg.bin",2048,NULL);
    acww_cloud_f0_map=read_asset("acww_sky/sky/d_2d_b_cld_f0_bg_nsc.bin",1024,NULL);
    acww_cloud_c0_map=read_asset("acww_sky/sky/d_2d_b_cld_c0_bg_nsc.bin",1024,NULL);
    acww_cloud_r0_map=read_asset("acww_sky/sky/d_2d_b_cld_r0_bg_nsc.bin",1024,NULL);
    acww_cloud_f0_palette=read_asset("acww_sky/sky/d_2d_b_cld_f0_bg_ncl.bin",384,NULL);
    acww_cloud_r0_palette=read_asset("acww_sky/sky/d_2d_b_cld_r0_bg_ncl.bin",384,NULL);
    int ok=sky_ncg && sky_ncl && sky_nsc && star_ncg && star_ncl && star_nsc && moon_ncg && moon_ncl &&
        acww_cloud_ncg && acww_cloud_f0_map && acww_cloud_c0_map && acww_cloud_r0_map &&
        acww_cloud_f0_palette && acww_cloud_r0_palette;
    if (ok) {
        unsigned char* sky=decode_sky_strip(sky_ncg,sky_ncl,sky_nsc);
        unsigned char* stars=decode_star_map(star_ncg,star_ncl,star_nsc);
        unsigned char* moon=decode_moon_sprite(moon_ncg,moon_ncl);
        unsigned char* clouds=decode_cloud_map(acww_cloud_ncg,acww_cloud_f0_palette,acww_cloud_f0_map,7);
        if (sky && stars && clouds && moon) {
            acww_sky_texture=upload_rgba_texture(512,128,sky);
            acww_star_texture=upload_rgba_texture(256,256,stars);
            acww_moon_texture=upload_rgba_texture(32,32,moon);
            acww_cloud_texture=upload_rgba_texture(256,128,clouds);
            acww_assets_ready=acww_sky_texture && acww_star_texture && acww_cloud_texture && acww_moon_texture;
            acww_cloud_variant=0; acww_palette_bank=7;
        }
        free(sky); free(stars); free(clouds); free(moon);
    }
    free(sky_ncg); free(sky_ncl); free(sky_nsc);
    free(star_ncg); free(star_ncl); free(star_nsc); free(moon_ncg); free(moon_ncl);
    if (!acww_assets_ready) {
        if (acww_sky_texture) glDeleteTextures(1,&acww_sky_texture);
        if (acww_cloud_texture) glDeleteTextures(1,&acww_cloud_texture);
        if (acww_star_texture) glDeleteTextures(1,&acww_star_texture);
        if (acww_moon_texture) glDeleteTextures(1,&acww_moon_texture);
        acww_sky_texture=acww_cloud_texture=acww_star_texture=acww_moon_texture=0;
        free(acww_cloud_ncg); free(acww_cloud_f0_map); free(acww_cloud_c0_map); free(acww_cloud_r0_map);
        free(acww_cloud_f0_palette); free(acww_cloud_r0_palette);
        acww_cloud_ncg=acww_cloud_f0_map=acww_cloud_c0_map=acww_cloud_r0_map=NULL;
        acww_cloud_f0_palette=acww_cloud_r0_palette=NULL;
    } else {
        fprintf(stdout,"[Sky] Imported Wild World sky layers loaded\n");
    }
    return acww_assets_ready;
}

static void update_wild_world_clouds(float game_seconds,float cloudiness) {
    if (!acww_assets_ready || !acww_cloud_texture) return;
    int bank=((int)(game_seconds/7200.0f))%12;
    if (bank<0) bank=0;
    int variant=cloudiness>0.68f ? 2 : (cloudiness>0.28f ? 1 : 0);
    if (bank==acww_palette_bank && variant==acww_cloud_variant) return;
    const unsigned char* map=variant==2 ? acww_cloud_r0_map :
                             variant==1 ? acww_cloud_c0_map : acww_cloud_f0_map;
    const unsigned char* palette=variant==2 ? acww_cloud_r0_palette : acww_cloud_f0_palette;
    unsigned char* pixels=decode_cloud_map(acww_cloud_ncg,palette,map,bank);
    if (!pixels) return;
    GLint previous_binding=0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D,&previous_binding);
    glBindTexture(GL_TEXTURE_2D,acww_cloud_texture);
    glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,128,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    glBindTexture(GL_TEXTURE_2D,(GLuint)previous_binding);
    free(pixels);
    acww_cloud_variant=variant;
    acww_palette_bank=bank;
}

static void free_wild_world_assets(void) {
    if (acww_sky_texture) glDeleteTextures(1,&acww_sky_texture);
    if (acww_cloud_texture) glDeleteTextures(1,&acww_cloud_texture);
    if (acww_star_texture) glDeleteTextures(1,&acww_star_texture);
    if (acww_moon_texture) glDeleteTextures(1,&acww_moon_texture);
    acww_sky_texture=acww_cloud_texture=acww_star_texture=acww_moon_texture=0;
    free(acww_cloud_ncg); free(acww_cloud_f0_map); free(acww_cloud_c0_map); free(acww_cloud_r0_map);
    free(acww_cloud_f0_palette); free(acww_cloud_r0_palette);
    acww_cloud_ncg=acww_cloud_f0_map=acww_cloud_c0_map=acww_cloud_r0_map=NULL;
    acww_cloud_f0_palette=acww_cloud_r0_palette=NULL;
    acww_assets_checked=acww_assets_ready=0;
    acww_cloud_variant=acww_palette_bank=-1;
}

extern "C" void pc_sky_set_environment(int enabled, float time, float cloudiness) {
    outdoors = enabled;
    seconds = time;
    overcast = cloudiness;
    environment_frame = pc_frame_counter;
    have_environment = 1;
    environment_active = enabled;
}

static void update_environment_active(void) {
    // Freeze the last live answer while paused; bypassing age checks outright
    // would revive an outdoor sky that already expired on a scene transition.
    if (!g_pc_paused)
        environment_active = have_environment && outdoors &&
            (uint32_t)(pc_frame_counter-environment_frame)<=1u;
}

extern "C" void pc_sky_begin_pass(void) {
    drawn = have_view = 0;
    update_environment_active(); // expire even when a scene has no perspective draws
}
extern "C" void pc_sky_set_view(const float* matrix) {
    memcpy(view,matrix,sizeof(view));
    have_view = 1;
}

extern "C" int pc_sky_draw(const float* projection, const float* correction) {
    update_environment_active();
    if (drawn || !have_view || !environment_active || !g_pc_settings.skybox) return 0;
    if (fabsf(projection[0])<0.00001f || fabsf(projection[5])<0.00001f) return 0;
    // Compose X*V for VR, then transpose its normalized rotation. Translation is
    // deliberately discarded, keeping clouds at infinity for both eyes and scales.
    float rotation[9];
    for (int r=0;r<3;r++) {
        float row[3];
        for (int c=0;c<3;c++) {
            row[c] = correction ? correction[r*4]*view[c] + correction[r*4+1]*view[4+c]
                                + correction[r*4+2]*view[8+c] : view[r*4+c];
        }
        float length = sqrtf(row[0]*row[0]+row[1]*row[1]+row[2]*row[2]);
        if (!(length>0.000001f) || !std::isfinite(length)) return 0;
        for (int c=0;c<3;c++) rotation[c*3+r]=row[c]/length;
    }
    if (!init()) return 0;
    if (!acww_assets_checked) {
        acww_assets_checked=1;
        load_wild_world_assets();
    }
    if (acww_assets_ready) update_wild_world_clouds(seconds,overcast);
    GLint previous_program, previous_vao, depth_func;
    GLint previous_active_texture, previous_texture0, previous_texture1, previous_texture2, previous_texture3;
    pc_gl_depth_value depth_range[2];
    GLboolean depth_mask, color_mask[4];
    GLboolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND);
    GLboolean cull = glIsEnabled(GL_CULL_FACE), scissor = glIsEnabled(GL_SCISSOR_TEST);
#ifndef __ANDROID__
    GLboolean logic = glIsEnabled(GL_COLOR_LOGIC_OP);
#endif
    glGetIntegerv(GL_CURRENT_PROGRAM,&previous_program);
    glGetIntegerv(GL_ACTIVE_TEXTURE,&previous_active_texture);
    glActiveTexture(GL_TEXTURE0); glGetIntegerv(GL_TEXTURE_BINDING_2D,&previous_texture0);
    glActiveTexture(GL_TEXTURE1); glGetIntegerv(GL_TEXTURE_BINDING_2D,&previous_texture1);
    glActiveTexture(GL_TEXTURE2); glGetIntegerv(GL_TEXTURE_BINDING_2D,&previous_texture2);
    glActiveTexture(GL_TEXTURE3); glGetIntegerv(GL_TEXTURE_BINDING_2D,&previous_texture3);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING,&previous_vao);
    glGetIntegerv(GL_DEPTH_FUNC,&depth_func);
    pc_gl_get_depth_range(depth_range);
    glGetBooleanv(GL_DEPTH_WRITEMASK,&depth_mask);
    glGetBooleanv(GL_COLOR_WRITEMASK,color_mask);
    glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(GL_FALSE);
    pc_gl_depth_range(0.0,1.0);
    glDisable(GL_BLEND); glDisable(GL_CULL_FACE); glDisable(GL_SCISSOR_TEST);
#ifndef __ANDROID__
    glDisable(GL_COLOR_LOGIC_OP);
#endif
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
    glUseProgram(program); glBindVertexArray(vao);
    glUniform4f(u_projection,projection[0],projection[5],projection[2],projection[6]);
    glUniformMatrix3fv(u_rotation,1,GL_TRUE,rotation);
    glUniform2f(u_environment,seconds,overcast);
    glUniform1i(u_acww_assets,acww_assets_ready ? 1 : 0);
    glUniform1i(u_acww_sky,0); glUniform1i(u_acww_clouds,1); glUniform1i(u_acww_stars,2); glUniform1i(u_acww_moon,3);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,acww_sky_texture);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D,acww_cloud_texture);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D,acww_star_texture);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D,acww_moon_texture);
    glDrawArrays(GL_TRIANGLES,0,3);
    // Exact restoration also preserves the GX renderer's cached state.
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,(GLuint)previous_texture0);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D,(GLuint)previous_texture1);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D,(GLuint)previous_texture2);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D,(GLuint)previous_texture3);
    glActiveTexture((GLenum)previous_active_texture);
    glUseProgram(previous_program); glBindVertexArray(previous_vao);
    glDepthFunc(depth_func); glDepthMask(depth_mask);
    pc_gl_depth_range(depth_range[0],depth_range[1]);
    glColorMask(color_mask[0],color_mask[1],color_mask[2],color_mask[3]);
    if (!depth) glDisable(GL_DEPTH_TEST);
    if (blend) glEnable(GL_BLEND);
    if (cull) glEnable(GL_CULL_FACE);
    if (scissor) glEnable(GL_SCISSOR_TEST);
#ifndef __ANDROID__
    if (logic) glEnable(GL_COLOR_LOGIC_OP);
#endif
    drawn = 1;
    return 1;
}

extern "C" void pc_sky_shutdown(void) {
    free_wild_world_assets();
    if (program) glDeleteProgram(program);
    if (vao) glDeleteVertexArrays(1,&vao);
    program=vao=0; failed=have_environment=have_view=drawn=environment_active=0;
}

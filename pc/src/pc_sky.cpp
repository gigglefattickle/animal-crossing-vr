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
    p.x += 0.012*sin(p.y*5.0+seed*8.0);
    p.y += 0.010*sin(p.x*7.0+seed*13.0);
    float d = oval(p, vec2(0.0,-0.06), vec2(1.18,0.23));
    d = min(d, oval(p, vec2(-0.56,0.06), vec2(0.52,0.28)));
    d = min(d, oval(p, vec2(-0.16,0.14+0.04*seed), vec2(0.60,0.34)));
    d = min(d, oval(p, vec2(0.42,0.08), vec2(0.62,0.27)));
    d = min(d, oval(p, vec2(0.88,-0.02), vec2(0.36,0.19)));
    return 1.0-smoothstep(0.91,1.10,d);
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

    /* Wild World cloud background is a 32x16 tile map (256x128 px).
     * The old 64x16 interpretation duplicated each row and created the
     * long stretched stripes reported on Quest. Reconstructed below using
     * the original 8bpp tile atlas and palette bank. */
    if (acww_assets != 0 && h > 0.035 && h < 0.86) {
        float drift = environment.x / 86400.0;
        vec2 cloudUV = vec2(fract(azimuth + drift * 0.035),
                            1.0 - clamp((h - 0.035) / 0.82, 0.0, 1.0));
        vec4 originalCloud = texture(acww_cloud_layer, cloudUV);
        float cloudAlpha = originalCloud.a * (1.0 - wet * 0.28);
        sky = mix(sky, originalCloud.rgb, cloudAlpha);
    }

    // Periodic longitude, bounded drift (same time for both eyes), no cube seams.
    if (h > 0.015 && h < 0.87 && acww_assets == 0) {
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
    /* The first imported cloud-map pass produced stretched tile bands on
     * Quest. Keep the original Wild World star/moon art, but render the
     * cloud banks with the stable procedural painterly layer until the NDS
     * screen-map attributes are fully reconstructed. */
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


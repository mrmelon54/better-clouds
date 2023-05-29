#version 430

#define BLIT_DEPTH _BLIT_DEPTH_
#define REMAP_DEPTH _REMAP_DEPTH_

#if !BLIT_DEPTH
layout(early_fragment_tests) in;
#endif

in vec3 pass_dir;
in vec2 pass_uv;

layout (location=0) out vec4 out_color;

#if BLIT_DEPTH
uniform sampler2D u_depth_texture;
#if REMAP_DEPTH
uniform vec4 u_depth_transform;
#endif
#endif

uniform sampler2D u_data_texture;
uniform usampler2D u_coverage_texture;
uniform sampler2D u_light_texture;
// x, y, z, tilt
uniform vec4 u_sun_direction;
// opacity, opacity factor
uniform vec2 u_opacity;
// brightness, gamma, desaturated brightness, saturation
uniform vec4 u_color_grading;
// r, g, b
uniform vec3 u_tint;

const float pi = 3.14159265359;
const float sqrt2 = 1.41421356237;

float linearize_depth(float hyp, float a, float b)
{
    return (hyp * 2 - 1) * a + b;
}

float hyperbolize_depth(float lin, float a, float b)
{
    return (lin * a + b) * 0.5 + 0.5;
}

float remap_depth(float d, float x, float y, float z, float w)
{
    return d*x*z - 0.5*x*z + 0.5*y*z + 0.5*w + 0.5;
}

void main() {
    vec3 cloudData = texelFetch(u_data_texture, ivec2(gl_FragCoord), 0).rgb;
    #if BLIT_DEPTH
    if(cloudData == vec3(0.0)) discard;
    #else
    if(cloudData == vec3(0.0)) return;
    #endif

    float coverage = float(texelFetch(u_coverage_texture, ivec2(gl_FragCoord), 0).r);
    // This is the "correct" formula
    // frag_color.a = 1. - pow((1.-u_opacity.a), coverage);
    out_color.a = pow(coverage, 1.5) / (1./(u_opacity.x)+pow(coverage, 1.5)-1);

    vec3 sunDir = u_sun_direction.xyz;
    vec3 fragDir = normalize(pass_dir);

    vec3 xzProj = fragDir - sunDir * dot(fragDir, sunDir);
    float projAngle = acos(dot(normalize(xzProj), vec3(0, 0, 1)));

    // if sunDir.z is always 0, this can be optimized, but who cares
    float sphere = dot(sunDir, fragDir);
    // TODO: document how I arrived at this formula
    float superellipse = ((1.0 + (1./3.) * (pow(sin(2*projAngle + pi/2.), 2.0))) * (1.-abs(dot(sunDir, fragDir))) - 1.0) * sign(dot(sunDir, -fragDir));
    float lightUVx = mix(sphere, superellipse, smoothstep(0.75, 1.0, abs(sphere)));

    // (1, 0) to (0.5, 1)
    if(lightUVx > 0.5) lightUVx = (-2 * lightUVx + 2) * 0.375;
    // (0.5, 0) to (-0.5, 1)
    else if(lightUVx > -0.5) lightUVx = 0.375 + (-1 * lightUVx + 0.5) * 0.25;
    // (-0.5, 0) to (-1, 1)
    else lightUVx = 0.625 + (-2 * lightUVx - 1) * 0.375;

    vec2 lightUV = vec2(lightUVx, u_sun_direction.w);

    // Prevent sampling the horizontally interpolated vertical edges
    lightUV.x -= (lightUV.x - 0.5) / textureSize(u_light_texture, 0).x;
    out_color.rgb = texture(u_light_texture, lightUV).rgb;

    float colorLumi = dot(out_color.rgb, vec3(0.2126, 0.7152, 0.072)) + 0.001;
    vec3 colorChroma = out_color.rgb / colorLumi;

    float colorVariance = length(vec2(1. - pow(1. - cloudData.g, 3.) * 0.75, cloudData.b * 0.75 + 0.25)) / sqrt2;
    colorLumi = colorVariance * 0.35 * (0.3 + 0.7 * colorLumi) + 0.75 * colorLumi;

    colorChroma = mix(vec3(1.0), colorChroma, u_color_grading.z);
    colorChroma = mix(vec3(1.0), colorChroma, u_color_grading.w);
    colorLumi *= u_color_grading.z;
    colorLumi *= u_color_grading.x;
    colorLumi = pow(colorLumi, u_color_grading.y);

    out_color.rgb = colorChroma * colorLumi;
    out_color.rgb *= u_tint;
    out_color.a *= u_opacity.y;

#if BLIT_DEPTH
#if REMAP_DEPTH
//    gl_FragDepth = hyperbolize_depth(linearize_depth(texture(u_depth, pass_uv).r, u_depthCoeffs.x, u_depthCoeffs.y), u_depthCoeffs.z, u_depthCoeffs.w);
    gl_FragDepth = remap_depth(texture(u_depth_texture, pass_uv).r, u_depth_transform.x, u_depth_transform.y, u_depth_transform.z, u_depth_transform.w);
#else
    gl_FragDepth = texture(u_depth_texture, pass_uv).r;
#endif
#endif
}
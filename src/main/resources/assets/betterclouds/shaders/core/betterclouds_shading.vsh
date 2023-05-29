#version 430

//layout(location = 0) in vec2 in_vert;
layout(location = 0) in vec3 in_vert;

out vec3 pass_dir;
out vec2 pass_uv;

uniform mat4 u_inverse_vp_matrix;

void main() {
    pass_dir = in_vert;

    gl_Position = u_inverse_vp_matrix * vec4(in_vert, 1.0);
    gl_Position.z = gl_Position.w;

    vec3 ndc = gl_Position.xyz / gl_Position.w;
    pass_uv = ndc.xy * 0.5 + 0.5;
}

#version 330

layout(location = 0) in vec2 in_vert;

out vec3 pass_dir;
out vec2 pass_uv;

uniform mat4 u_inverseMat;

void main() {
    vec4 dir = u_inverseMat * vec4(in_vert, 0.0, 1.0);
    pass_dir = dir.xyz / dir.w;
    pass_uv = in_vert * 0.5 + 0.5;

    gl_Position = vec4(in_vert, 0.0, 1.0);
}
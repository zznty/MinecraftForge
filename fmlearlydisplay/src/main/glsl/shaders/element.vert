#version 450 core

layout(location = 0) in vec3 position;
layout(location = 1) in vec2 tex;
layout(location = 2) in vec4 colour;

layout(push_constant) uniform PushConstants {
    vec2 screenSize;
    int rendertype;
    int textureNumber;
} pc;

layout(location = 0) out vec2 fTex;
layout(location = 1) out vec4 fColour;

void main() {
    fTex = tex;
    fColour = colour;
    gl_Position = vec4((position.xy / pc.screenSize) * 2.0 - 1.0, position.z, 1.0);
}

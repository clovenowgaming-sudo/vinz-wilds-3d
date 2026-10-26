package com.vinz.wilds;

/** All GLSL ES 3.00 sources. */
final class Shaders {
    private Shaders() {}

    static final String VH = "#version 300 es\nprecision highp float;\nprecision highp int;\n";
    static final String FH = "#version 300 es\nprecision highp float;\nprecision highp int;\n"
            + "precision highp sampler2D;\nprecision highp sampler2DShadow;\n";
    static final String SH_FS = "#version 300 es\nprecision mediump float;\nvoid main(){}\n";

    // ------------------------------------------------------------------ common (fragment)
    static final String COMMON = """
#define PI 3.14159265
uniform vec3 uCam;
uniform float uTime;
uniform vec3 uSunDir;
uniform vec3 uLightDir;
uniform vec3 uLightCol;
uniform vec3 uAmbSky;
uniform vec3 uAmbGnd;
uniform vec3 uSkyZen;
uniform vec3 uSkyHor;
uniform vec3 uSunDisc;
uniform vec3 uSunTint;
uniform float uDay;
uniform float uNight;
uniform float uSunVis;
uniform float uFog;
uniform int uQ;
uniform float uShadowOn;
uniform float uTexel;
uniform sampler2DShadow uShadow;

float hash21(vec2 p){ p = fract(p*vec2(123.34,456.21)); p += dot(p,p+45.32); return fract(p.x*p.y); }
float hash31(vec3 p){ p = fract(p*vec3(0.1031,0.1030,0.0973)); p += dot(p,p.yxz+33.33); return fract((p.x+p.y)*p.z); }
float vnoise(vec2 p){
  vec2 i = floor(p); vec2 f = fract(p); f = f*f*(3.0-2.0*f);
  float a = hash21(i); float b = hash21(i+vec2(1.0,0.0));
  float c = hash21(i+vec2(0.0,1.0)); float d = hash21(i+vec2(1.0,1.0));
  return mix(mix(a,b,f.x), mix(c,d,f.x), f.y);
}
float fbmN(vec2 p, int o){
  float s = 0.0; float a = 0.5;
  for(int i=0;i<5;i++){ if(i>=o) break; s += a*vnoise(p); p = p*2.03+vec2(17.3,9.1); a *= 0.5; }
  return s;
}

vec3 skyBase(vec3 d){
  float h = clamp(d.y,0.0,1.0);
  vec3 col = mix(uSkyHor, uSkyZen, pow(h,0.5));
  float sd = max(dot(d,uSunDir),0.0);
  col += uSunDisc*(pow(sd,6.0)*0.10 + pow(sd,48.0)*0.35)*uSunVis;
  col = mix(col, uSkyHor*0.55, clamp(-d.y*4.0,0.0,1.0));
  return col;
}

vec3 skyFull(vec3 d){
  vec3 col = skyBase(d);
  float sd = max(dot(d,uSunDir),0.0);
  col += uSunDisc*smoothstep(0.99955,0.99985,sd)*18.0*uSunVis;
  float md = max(dot(d,-uSunDir),0.0);
  col += vec3(0.75,0.82,1.0)*smoothstep(0.9993,0.9997,md)*2.5*uNight;
  if(d.y>0.0){
    if(uNight>0.02){
      vec3 q = floor(d*140.0);
      float h = hash31(q);
      float tw = 0.6+0.4*sin(uTime*2.0+h*50.0);
      float star = step(0.9965,h)*tw*smoothstep(0.0,0.15,d.y);
      col += vec3(0.9,0.95,1.0)*star*uNight*1.6;
    }
    if(uQ>0){
      float t = 700.0/max(d.y,0.03);
      vec2 cp = (uCam.xz + d.xz*t)*0.0011 + vec2(uTime*0.006,uTime*0.002);
      float cd = (uQ>=2) ? fbmN(cp,5) : fbmN(cp,3);
      float cov = smoothstep(0.50,0.78,cd);
      float lit = 0.55+0.45*clamp(uSunDir.y+0.35,0.0,1.0);
      vec3 cc = mix(vec3(0.06,0.07,0.11), mix(vec3(1.0),uSunTint,0.55)*lit*1.1, uDay);
      cc *= 0.75+0.35*fbmN(cp*3.0+7.0,2);
      float fade = smoothstep(0.02,0.22,d.y);
      col = mix(col, cc, cov*fade*0.92);
    }
  }
  return col;
}

float shadowFactor(vec4 sp, float ndl){
  if(uShadowOn<0.5) return 1.0;
  vec3 pr = sp.xyz/sp.w*0.5+0.5;
  if(pr.z>1.0 || pr.x<0.0 || pr.x>1.0 || pr.y<0.0 || pr.y>1.0) return 1.0;
  float rz = pr.z - (0.0006 + 0.0016*(1.0-ndl));
  float s = 0.0;
  if(uQ>=2){
    for(int x=-1;x<=1;x++){
      for(int y=-1;y<=1;y++){
        s += texture(uShadow, vec3(pr.xy + vec2(float(x),float(y))*uTexel, rz));
      }
    }
    s /= 9.0;
  } else {
    s = texture(uShadow, vec3(pr.xy, rz));
    s += texture(uShadow, vec3(pr.xy + vec2(uTexel,uTexel)*0.8, rz));
    s += texture(uShadow, vec3(pr.xy - vec2(uTexel,uTexel)*0.8, rz));
    s *= 0.33333;
  }
  float fade = smoothstep(0.0,0.08,min(min(pr.x,1.0-pr.x),min(pr.y,1.0-pr.y)));
  return mix(1.0,s,fade);
}

vec3 pbr(vec3 alb, float rough, float metal, vec3 N, vec3 V, vec3 L, vec3 lc, float sh, float wrap){
  vec3 H = normalize(V+L);
  float ndl = dot(N,L);
  float ndlc = max(ndl,0.0);
  float ndv = max(dot(N,V),1e-3);
  float ndh = max(dot(N,H),0.0);
  float vdh = max(dot(V,H),0.0);
  float a = rough*rough; float a2 = a*a;
  float dd = ndh*ndh*(a2-1.0)+1.0;
  float D = a2/(PI*dd*dd+1e-5);
  float k = (rough+1.0); k = k*k/8.0;
  float G = (ndlc/(ndlc*(1.0-k)+k+1e-4))*(ndv/(ndv*(1.0-k)+k+1e-4));
  vec3 F0 = mix(vec3(0.04),alb,metal);
  vec3 F = F0+(1.0-F0)*pow(1.0-vdh,5.0);
  vec3 spec = D*G*F/(4.0*ndlc*ndv+1e-3);
  vec3 kd = (1.0-F)*(1.0-metal);
  float dif = max((ndl+wrap)/(1.0+wrap),0.0);
  return (kd*alb/PI*dif + spec*ndlc)*lc*sh;
}

vec3 ambientL(vec3 alb, vec3 N, float metal){
  return mix(uAmbGnd,uAmbSky,N.y*0.5+0.5)*alb*(1.0-metal*0.7);
}

vec3 aces(vec3 x){ return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0); }
vec3 finalColor(vec3 c){ return pow(aces(c), vec3(1.0/2.2)); }

vec3 applyFog(vec3 col, vec3 wpos){
  vec3 v = wpos - uCam;
  float d = length(v);
  float f = 1.0 - exp(-d*uFog);
  float hf = exp(-max(wpos.y,0.0)*0.015);
  f = clamp(f*(0.55+0.45*hf),0.0,1.0);
  vec3 fc = skyBase(v/max(d,0.001));
  return mix(col, fc, f);
}
""";

    // ------------------------------------------------------------------ terrain
    static final String TERRAIN_VS = """
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
uniform mat4 uVP;
uniform mat4 uLightVP;
out vec3 vWorld;
out vec3 vNrm;
out vec4 vSh;
void main(){
  vWorld = aPos;
  vNrm = aNrm;
  vSh = uLightVP*vec4(aPos+aNrm*0.25,1.0);
  gl_Position = uVP*vec4(aPos,1.0);
}
""";

    static final String SH_TERR_VS = """
layout(location=0) in vec3 aPos;
uniform mat4 uLightVP;
void main(){ gl_Position = uLightVP*vec4(aPos,1.0); }
""";

    static final String TERRAIN_FS = """
in vec3 vWorld;
in vec3 vNrm;
in vec4 vSh;
out vec4 frag;
void main(){
  vec3 N = normalize(vNrm);
  vec3 V = normalize(uCam - vWorld);
  float h = vWorld.y;
  vec2 p = vWorld.xz;
  float slope = 1.0 - N.y;
  float n1 = (uQ>0) ? fbmN(p*0.30,3) : vnoise(p*0.30);
  float n2 = vnoise(p*2.3);
  float n3 = (uQ>=2) ? vnoise(p*9.0) : 0.5;
  if(uQ>=1){
    float e = 0.12;
    float gx = vnoise((p+vec2(e,0.0))*3.1) - vnoise((p-vec2(e,0.0))*3.1);
    float gz = vnoise((p+vec2(0.0,e))*3.1) - vnoise((p-vec2(0.0,e))*3.1);
    float amp = (uQ>=2) ? 1.6 : 0.9;
    N = normalize(N + vec3(-gx,0.0,-gz)*amp);
  }
  vec3 grass = mix(vec3(0.06,0.14,0.03), vec3(0.20,0.33,0.07), clamp(n1*1.1+n2*0.35,0.0,1.0));
  grass *= 0.85+0.3*n3;
  grass = mix(grass, vec3(0.22,0.19,0.08), smoothstep(0.55,0.85,n1)*0.55);
  vec3 dirt = vec3(0.17,0.115,0.07)*(0.7+0.6*n2);
  vec3 sand = vec3(0.50,0.43,0.28)*(0.85+0.3*n2);
  vec3 rock = vec3(0.17,0.165,0.16)*(0.6+0.8*n1)*(0.8+0.4*n3);
  vec3 snow = vec3(0.80,0.84,0.90);
  float wSand = 1.0 - smoothstep(0.2,1.8,h + (n1-0.5)*1.6);
  float wRock = smoothstep(0.14,0.30,slope + (n2-0.5)*0.12);
  float wSnow = smoothstep(40.0,52.0,h + (n1-0.5)*10.0)*(1.0-smoothstep(0.20,0.34,slope));
  float wDirt = smoothstep(0.62,0.9,n2*0.6+n1*0.6)*0.5;
  vec3 alb = mix(grass, dirt, wDirt);
  alb = mix(alb, rock, wRock);
  alb = mix(alb, sand, wSand*(1.0-wRock*0.5));
  alb = mix(alb, snow, wSnow);
  float rough = mix(0.92, 0.7, wRock);
  rough = mix(rough, 0.5, wSnow);
  rough = mix(rough, 0.97, wSand);
  float ndl = dot(N,uLightDir);
  float sh = shadowFactor(vSh, max(ndl,0.0));
  vec3 col = pbr(alb, rough, 0.0, N, V, uLightDir, uLightCol, sh, 0.0);
  float ao = mix(1.0,0.75,clamp(slope*1.4,0.0,1.0));
  col += ambientL(alb, N, 0.0)*ao;
  if(h<0.0){
    float dp = -h;
    col = mix(col, vec3(0.01,0.06,0.10)*(0.25+uDay), (1.0-exp(-dp*0.3))*0.8);
  }
  frag = vec4(finalColor(applyFog(col, vWorld)),1.0);
}
""";

    // ------------------------------------------------------------------ instanced objects
    static final String OBJ_VS = """
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
layout(location=2) in vec4 aCol;
layout(location=3) in vec4 iPS;
layout(location=4) in float iRot;
uniform mat4 uVP;
uniform mat4 uLightVP;
uniform vec3 uCam;
uniform float uTime;
uniform float uSway;
uniform float uBob;
uniform float uCull;
uniform float uFadeScale;
out vec3 vWorld;
out vec3 vNrm;
out vec4 vCol;
out vec4 vSh;
void main(){
  float d = distance(iPS.xz, uCam.xz);
  if(d > uCull){
    gl_Position = vec4(2.0,2.0,2.0,1.0);
    vWorld = vec3(0.0); vNrm = vec3(0.0,1.0,0.0); vCol = vec4(0.0); vSh = vec4(0.0);
    return;
  }
  float fade = 1.0 - smoothstep(uCull*0.7, uCull, d);
  float c = cos(iRot);
  float s = sin(iRot);
  vec3 p = aPos*iPS.w;
  p.y *= mix(1.0, fade, uFadeScale);
  float hgt = max(p.y, 0.0);
  float ph = iPS.x*0.37 + iPS.z*0.29;
  float k = uSway*hgt*hgt*0.004;
  p.x += sin(uTime*1.6+ph)*k;
  p.z += cos(uTime*1.3+ph*1.3)*k*0.7;
  p.y += sin(uTime*2.0+ph)*uBob;
  vec3 wp = vec3(p.x*c + p.z*s, p.y, -p.x*s + p.z*c) + iPS.xyz;
  vec3 n = vec3(aNrm.x*c + aNrm.z*s, aNrm.y, -aNrm.x*s + aNrm.z*c);
  vWorld = wp;
  vNrm = n;
  vCol = aCol;
#ifdef SHADOW
  gl_Position = uLightVP*vec4(wp,1.0);
  vSh = vec4(0.0);
#else
  gl_Position = uVP*vec4(wp,1.0);
  vSh = uLightVP*vec4(wp + n*0.12, 1.0);
#endif
}
""";

    static final String OBJ_FS = """
in vec3 vWorld;
in vec3 vNrm;
in vec4 vCol;
in vec4 vSh;
uniform float uRough;
uniform float uMetal;
uniform float uWrap;
uniform float uEmit;
uniform float uTwoSided;
out vec4 frag;
void main(){
  vec3 N = normalize(vNrm);
  vec3 V = normalize(uCam - vWorld);
  if(uTwoSided>0.5 && dot(N,V)<0.0) N = -N;
  vec3 alb = vCol.rgb;
  if(uQ>0){
    float nv = vnoise(vWorld.xz*1.3 + vWorld.y*0.7);
    alb *= 0.82+0.36*nv;
  }
  float ndl = dot(N,uLightDir);
  float sh = shadowFactor(vSh, max(ndl,0.0));
  vec3 col = pbr(alb, uRough, uMetal, N, V, uLightDir, uLightCol, sh, uWrap);
  col += ambientL(alb, N, uMetal);
  if(uQ>=1){
    vec3 R = reflect(-V,N);
    col += skyBase(R)*(0.04+0.9*uMetal)*(1.0-uRough)*0.6*(0.3+0.7*uDay);
  }
  col += vCol.rgb*vCol.a*uEmit;
  frag = vec4(finalColor(applyFog(col, vWorld)),1.0);
}
""";

    // ------------------------------------------------------------------ water
    static final String WATER_VS = """
layout(location=0) in vec3 aPos;
uniform mat4 uVP;
out vec3 vWorld;
void main(){
  vWorld = aPos;
  gl_Position = uVP*vec4(aPos,1.0);
}
""";

    static final String WATER_FS = """
in vec3 vWorld;
uniform sampler2D uHeightTex;
uniform float uWorld;
out vec4 frag;
float waveH(vec2 p){
  float t = uTime;
  float h = sin(dot(p,vec2(0.8,0.6))*0.9+t*1.6)*0.5;
  h += sin(dot(p,vec2(-0.6,0.8))*1.7+t*2.1)*0.25;
  if(uQ>0) h += vnoise(p*0.8+vec2(t*0.5,t*0.3))*0.4;
  if(uQ>1) h += vnoise(p*3.0-t*vec2(0.8,0.5))*0.15;
  return h*0.5;
}
void main(){
  vec3 V = normalize(uCam - vWorld);
  float dist = length(uCam - vWorld);
  vec2 p = vWorld.xz;
  float e = 0.06;
  float h0 = waveH(p);
  float hx = waveH(p+vec2(e,0.0));
  float hz = waveH(p+vec2(0.0,e));
  vec3 N = normalize(vec3((h0-hx)/e*0.45, 1.0, (h0-hz)/e*0.45));
  N = normalize(mix(N, vec3(0.0,1.0,0.0), clamp(dist/260.0,0.0,1.0)));
  float th = texture(uHeightTex, p/uWorld + 0.5).r;
  float depth = max(-th, 0.0);
  vec3 R = reflect(-V,N);
  R.y = abs(R.y);
  vec3 refl = (uQ>0) ? skyFull(R) : skyBase(R);
  float F = 0.02 + 0.98*pow(1.0-max(dot(N,V),0.0),5.0);
  float ls = 0.12 + 0.88*uDay;
  vec3 shallow = vec3(0.10,0.45,0.48)*ls;
  vec3 deep = vec3(0.01,0.07,0.16)*ls;
  vec3 body = mix(shallow, deep, 1.0-exp(-depth*0.35));
  vec3 col = mix(body, refl, F);
  vec3 H = normalize(V+uLightDir);
  float sp = pow(max(dot(N,H),0.0),180.0);
  col += uLightCol*sp*((uQ>0)?0.6:0.3);
  float foam = (1.0-smoothstep(0.0,0.9,depth))*(0.5+0.5*vnoise(p*1.5+uTime*0.8));
  col = mix(col, vec3(0.9,0.95,1.0)*ls, foam*0.7);
  float alpha = clamp(depth*1.2,0.0,1.0)*0.7+0.3;
  alpha = max(alpha, foam*0.85);
  alpha = max(alpha, F);
  col = applyFog(col, vWorld);
  frag = vec4(finalColor(col), clamp(alpha,0.0,1.0));
}
""";

    // ------------------------------------------------------------------ sky
    static final String SKY_VS = """
uniform mat4 uInvVP;
uniform vec3 uCam;
out vec3 vDir;
void main(){
  vec2 p = vec2(float((gl_VertexID<<1)&2), float(gl_VertexID&2));
  vec2 ndc = p*2.0-1.0;
  gl_Position = vec4(ndc,1.0,1.0);
  vec4 w = uInvVP*vec4(ndc,1.0,1.0);
  vDir = w.xyz/w.w - uCam;
}
""";

    static final String SKY_FS = """
in vec3 vDir;
out vec4 frag;
void main(){
  frag = vec4(finalColor(skyFull(normalize(vDir))),1.0);
}
""";

    // ------------------------------------------------------------------ weapon (view space)
    static final String GUN_VS = """
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
layout(location=2) in vec4 aCol;
uniform mat4 uProj;
uniform mat4 uModel;
out vec3 vN;
out vec4 vC;
void main(){
  vN = mat3(uModel)*aNrm;
  vC = aCol;
  gl_Position = uProj*(uModel*vec4(aPos,1.0));
}
""";

    static final String GUN_FS = """
in vec3 vN;
in vec4 vC;
uniform vec3 uLv;
uniform float uLevel;
out vec4 frag;
void main(){
  vec3 N = normalize(vN);
  float d = max(dot(N,normalize(uLv)),0.0);
  float rim = pow(1.0-max(N.z,0.0),3.0);
  vec3 col = vC.rgb*(0.10 + uLevel*(0.9*d + 0.12*(N.y*0.5+0.5)));
  col += vec3(0.5,0.6,0.8)*rim*0.06*uLevel;
  col += vC.rgb*vC.a*1.6;
  col = pow(clamp(col,0.0,1.0), vec3(1.0/2.2));
  frag = vec4(col,1.0);
}
""";

    // ------------------------------------------------------------------ post processing
    static final String QUAD_VS = """
out vec2 vUV;
void main(){
  vec2 p = vec2(float((gl_VertexID<<1)&2), float(gl_VertexID&2));
  vUV = p;
  gl_Position = vec4(p*2.0-1.0,0.0,1.0);
}
""";

    static final String BRIGHT_FS = """
uniform sampler2D uTex;
uniform vec2 uInv;
in vec2 vUV;
out vec4 frag;
void main(){
  vec3 c = texture(uTex, vUV+uInv*vec2(-1.0,-1.0)).rgb
         + texture(uTex, vUV+uInv*vec2( 1.0,-1.0)).rgb
         + texture(uTex, vUV+uInv*vec2(-1.0, 1.0)).rgb
         + texture(uTex, vUV+uInv*vec2( 1.0, 1.0)).rgb;
  c *= 0.25;
  float l = dot(c, vec3(0.299,0.587,0.114));
  float k = smoothstep(0.62,0.98,l);
  frag = vec4(c*k,1.0);
}
""";

    static final String BLUR_FS = """
uniform sampler2D uTex;
uniform vec2 uDir;
in vec2 vUV;
out vec4 frag;
void main(){
  vec3 s = texture(uTex,vUV).rgb*0.227027;
  vec2 o1 = uDir*1.3846;
  vec2 o2 = uDir*3.2308;
  s += texture(uTex,vUV+o1).rgb*0.3162162;
  s += texture(uTex,vUV-o1).rgb*0.3162162;
  s += texture(uTex,vUV+o2).rgb*0.0702703;
  s += texture(uTex,vUV-o2).rgb*0.0702703;
  frag = vec4(s,1.0);
}
""";

    static final String COMP_FS = """
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform vec2 uInv;
uniform vec2 uSun;
uniform float uShaft;
uniform float uBloomK;
uniform float uTime;
in vec2 vUV;
out vec4 frag;
float luma(vec3 c){ return dot(c,vec3(0.299,0.587,0.114)); }
float hsh(vec2 p){ p = fract(p*vec2(123.34,456.21)); p += dot(p,p+45.32); return fract(p.x*p.y); }
void main(){
  vec2 uv = vUV;
  vec3 c  = texture(uScene,uv).rgb;
  vec3 cn = texture(uScene,uv+vec2(0.0,-uInv.y)).rgb;
  vec3 cs = texture(uScene,uv+vec2(0.0, uInv.y)).rgb;
  vec3 ce = texture(uScene,uv+vec2( uInv.x,0.0)).rgb;
  vec3 cw = texture(uScene,uv+vec2(-uInv.x,0.0)).rgb;
  float lm = luma(c); float ln = luma(cn); float ls = luma(cs); float le = luma(ce); float lw = luma(cw);
  float lmin = min(lm,min(min(ln,ls),min(le,lw)));
  float lmax = max(lm,max(max(ln,ls),max(le,lw)));
  float blend = smoothstep(0.04,0.25,lmax-lmin)*0.7;
  c = mix(c, (cn+cs+ce+cw+c)*0.2, blend);
  c += texture(uBloom,uv).rgb*uBloomK;
  if(uShaft>0.001){
    vec2 dir = (uSun-uv)/24.0*0.85;
    vec2 p = uv;
    vec3 acc = vec3(0.0);
    float w = 1.0;
    for(int i=0;i<24;i++){ p += dir; acc += texture(uBloom,p).rgb*w; w *= 0.94; }
    c += acc/24.0*uShaft*1.3*vec3(1.0,0.9,0.75);
  }
  float vg = 1.0 - 0.38*pow(length((uv-0.5)*vec2(1.0,0.85))*1.25,2.2);
  c *= clamp(vg,0.0,1.0);
  float l = luma(c);
  c = mix(vec3(l),c,1.12);
  c += (hsh(uv*vec2(1000.0)+uTime)-0.5)*0.012;
  frag = vec4(clamp(c,0.0,1.0),1.0);
}
""";

    // ------------------------------------------------------------------ HUD
    static final String HUD_VS = """
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUV;
layout(location=2) in vec4 aCol;
uniform float uAspect;
out vec2 vUV;
out vec4 vCol;
void main(){
  vUV = aUV;
  vCol = aCol;
  gl_Position = vec4(aPos.x/(10.0*uAspect)*2.0-1.0, 1.0-aPos.y/10.0*2.0, 0.0, 1.0);
}
""";

    static final String HUD_FS = """
uniform sampler2D uTex;
in vec2 vUV;
in vec4 vCol;
out vec4 frag;
void main(){
  vec4 t = texture(uTex, vUV);
  frag = vec4(vCol.rgb*t.rgb*vCol.a, t.a*vCol.a);
}
""";
}

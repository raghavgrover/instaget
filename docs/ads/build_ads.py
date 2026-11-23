#!/usr/bin/env python3
"""Render InstGet Meta ad creatives (1080x1350) via headless Chrome."""
import subprocess, sys, pathlib

HERE = pathlib.Path(__file__).parent
OUT = pathlib.Path("/Users/raghavg/github_repos/instaget/docs/ads")
OUT.mkdir(parents=True, exist_ok=True)
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ICON = "file:///Users/raghavg/github_repos/instaget/docs/store-screenshots/icon_512.png"

COPY = {
    "en": dict(
        lang="en",
        headA="Save Videos<br>&amp; Photos<br>to your phone",
        free="100% FREE",
        tap="Tap <b>Install</b>",
        store="Get InstGet on Play Store",
        headB="Love a video?<br><span>Save it.</span>",
        subB="Videos &amp; photos —<br>straight to your gallery",
        chip2="No login",
        headC="Save Videos<br>&amp; Photos", subC="straight to your phone",
    ),
    "hi": dict(
        lang="hi",
        headA="वीडियो और फ़ोटो<br>सीधे अपने फ़ोन<br>में सेव करें",
        free="100% फ़्री",
        tap="<b>Install</b> बटन दबाएँ",
        store="Play Store से InstGet डाउनलोड करें",
        headB="वीडियो पसंद आया?<br><span>सेव कर लो।</span>",
        subB="वीडियो और फ़ोटो —<br>सीधे आपकी गैलरी में",
        chip2="बिना लॉगिन",
        headC="वीडियो और फ़ोटो<br>सेव करें", subC="सीधे अपने फ़ोन में",
    ),
}

BASE = """<!doctype html><html lang="LANG"><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Poppins:wght@500;600;700;800&display=block" rel="stylesheet">
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:1080px;height:1350px;overflow:hidden}
body{font-family:'Poppins','Kohinoor Devanagari',sans-serif;position:relative}
.purple{background:linear-gradient(150deg,#8A3FFF 0%,#5904EB 48%,#3600B4 100%);color:#fff}
.glow{position:absolute;left:-200px;top:-200px;width:800px;height:800px;border-radius:50%;
  background:radial-gradient(circle,rgba(255,255,255,.28),rgba(255,255,255,0) 70%)}
.orb{position:absolute;border-radius:50%;background:rgba(255,255,255,.08)}
.brand{position:absolute;left:80px;top:76px;display:flex;align-items:center;gap:24px;font-weight:700;font-size:54px;letter-spacing:-1px}
.brand img{width:104px;height:104px;border-radius:50%;box-shadow:0 12px 30px rgba(30,0,110,.35)}
h1{position:absolute;left:80px;font-weight:800;letter-spacing:-2px}
.hi h1{letter-spacing:0}
.pill{position:absolute;left:80px;background:#FFD43B;color:#1b0a4a;font-weight:800;font-size:60px;
  padding:14px 44px;border-radius:999px;box-shadow:0 14px 34px rgba(20,0,90,.35)}
.cta{position:absolute;left:80px;bottom:84px}
.cta .tap{font-size:56px;font-weight:600;display:flex;align-items:center;gap:20px}
.cta .tap b{font-weight:800}
.cta .store{font-size:38px;font-weight:500;opacity:.9;margin-top:8px}
.arrow{width:68px;height:68px;border-radius:50%;background:#fff;display:inline-flex;align-items:center;justify-content:center}
.arrow svg{width:38px;height:38px}
</style></head><body class="CLS LANG">BODY</body></html>"""

ARROW = '<span class="arrow"><svg viewBox="0 0 24 24" fill="none" stroke="#5904EB" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v15M5 12l7 7 7-7"/></svg></span>'


def design_a(c):
    hs = 130 if c["lang"] == "en" else 124
    lh = 1.06 if c["lang"] == "en" else 1.3
    return "purple", f"""
<div class="glow"></div>
<div class="orb" style="width:560px;height:560px;right:-180px;bottom:-140px"></div>
<div class="orb" style="width:260px;height:260px;right:90px;top:-110px"></div>
<div class="brand"><img src="{ICON}">InstGet</div>
<h1 style="top:300px;font-size:{hs}px;line-height:{lh}">{c['headA']}</h1>
<div class="pill" style="top:850px">{c['free']}</div>
<div class="cta"><div class="tap">{ARROW}<span>{c['tap']}</span></div><div class="store">{c['store']}</div></div>
"""


def design_b(c):
    hs = 134 if c["lang"] == "en" else 106
    lh = 1.08 if c["lang"] == "en" else 1.32
    return "light", f"""
<style>
body.light{{background:linear-gradient(180deg,#ffffff 0%,#F1EAFF 100%);color:#15102b}}
.light .brand{{color:#15102b}}
.light h1 span{{color:#5904EB}}
.sub{{position:absolute;left:80px;font-size:50px;font-weight:500;line-height:1.35;color:#3d3757}}
.chips{{position:absolute;left:80px;display:flex;gap:22px}}
.chip{{font-size:46px;font-weight:700;padding:14px 38px;border-radius:999px;border:4px solid #15102b}}
.chip.f{{background:#15102b;color:#fff}}
.bar{{position:absolute;left:60px;right:60px;bottom:64px;height:190px;border-radius:44px;
  background:linear-gradient(120deg,#8A3FFF,#5904EB 55%,#3600B4);color:#fff;display:flex;align-items:center;gap:30px;padding:0 44px;
  box-shadow:0 22px 50px rgba(89,4,235,.35)}}
.bar .t{{font-size:50px;font-weight:600;line-height:1.15}}
.bar .t b{{font-weight:800}}
.bar .s{{font-size:33px;font-weight:500;opacity:.92;margin-top:6px}}
.bar .arrow{{width:96px;height:96px;flex:none}} .bar .arrow svg{{width:52px;height:52px}}
.big{{position:absolute;right:-110px;top:-120px;width:400px;height:400px;border-radius:50%;opacity:.95}}
</style>
<img class="big" src="{ICON}">
<div class="brand">InstGet</div>
<h1 style="top:270px;font-size:{hs}px;line-height:{lh}">{c['headB']}</h1>
<div class="sub" style="top:{690 if c['lang']=='en' else 720}px">{c['subB']}</div>
<div class="chips" style="top:{900 if c['lang']=='en' else 930}px"><div class="chip f">{c['free']}</div><div class="chip">{c['chip2']}</div></div>
<div class="bar">{ARROW}<div><div class="t">{c['tap']}</div><div class="s">{c['store']}</div></div></div>
"""


def design_c(c, person, ph=900, pr=-40):
    hs = 122 if c["lang"] == "en" else 106
    lh = 1.08 if c["lang"] == "en" else 1.32
    return "purple", f"""
<style>
.manvi{{position:absolute;right:{pr}px;bottom:0;height:{ph}px}}
.halo{{position:absolute;right:-160px;bottom:-200px;width:860px;height:860px;border-radius:50%;
  background:radial-gradient(circle,rgba(255,255,255,.30),rgba(255,255,255,.06) 62%,rgba(255,255,255,0) 70%)}}
.c .cta .store{{max-width:400px;line-height:1.25;font-size:34px}}
.c .cta .tap{{font-size:46px}}
.subc{{position:absolute;left:80px;font-size:50px;font-weight:600;opacity:.95}}
</style>
<div class="glow"></div>
<div class="halo"></div>
<div class="brand"><img src="{ICON}">InstGet</div>
<h1 style="top:250px;font-size:{hs}px;line-height:{lh}">{c['headC']}</h1>
<div class="subc" style="top:{520 if c['lang']=='en' else 540}px">{c['subC']}</div>
<div class="pill" style="top:{650 if c['lang']=='en' else 670}px;font-size:54px">{c['free']}</div>
<img class="manvi" src="{(HERE / person).as_uri()}">
<div class="cta"><div class="tap">{ARROW}<span>{c['tap']}</span></div><div class="store">{c['store']}</div></div>
"""


from functools import partial
DESIGNS = {"1_bold": design_a, "2_light": design_b,
           "3_manvi": partial(design_c, person="p_manvi.png", ph=850, pr=-30),
           "4_cs_a": partial(design_c, person="p_cs_a.png", ph=850, pr=-10),
           "5_cs_b": partial(design_c, person="p_cs_b.png", ph=790, pr=-70)}
only = sys.argv[1:]
for name, fn in DESIGNS.items():
    if only and name not in only:
        continue
    for lang, c in COPY.items():
        cls, body = fn(c)
        if name[0] in "345":
            cls += " c"
        html = BASE.replace("LANG", lang).replace("CLS", cls).replace("BODY", body)
        f = HERE / f"{name}_{lang}.html"
        f.write_text(html, encoding="utf-8")
        out = OUT / f"instget_ad_{name}_{lang}.png"
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        "--force-device-scale-factor=1", "--window-size=1080,1350",
                        "--virtual-time-budget=6000", "--allow-file-access-from-files",
                        f"--screenshot={out}", f.as_uri()],
                       check=True, capture_output=True)
        print(out)

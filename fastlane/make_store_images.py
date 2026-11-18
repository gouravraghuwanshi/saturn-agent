import sys, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter
SHOTS, OUT, FONTS = sys.argv[1], sys.argv[2], sys.argv[3]
GROUND=(11,12,15); TEXT=(233,230,223); MUTED=(140,138,133); AMBER=(242,179,91); INK=(20,17,11); LINE=(42,44,51)
inter=lambda s: ImageFont.truetype(os.path.join(FONTS,"inter.ttf"), s)
def bold(s):
    f=inter(s)
    try: f.set_variation_by_axes([32, 700])
    except Exception: pass
    return f
def reg(s):
    f=inter(s)
    try: f.set_variation_by_axes([14, 400])
    except Exception: pass
    return f

def saturn(size, bg=True):
    S=size*4
    im=Image.new("RGBA",(S,S),AMBER+(255,) if bg else (0,0,0,0))
    k=S/108
    def ring(half):
        layer=Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(layer)
        box=[(54-29)*k,(54-9.5)*k,(54+29)*k,(54+9.5)*k]
        if half: d.arc(box,0,180,fill=INK,width=int(4.5*k))
        else: d.ellipse(box,outline=INK,width=int(4.5*k))
        return layer.rotate(22,center=(S/2,S/2),resample=Image.BICUBIC)
    im.alpha_composite(ring(False))
    d=ImageDraw.Draw(im); r=14.5*k
    d.ellipse([S/2-r-2*k,S/2-r-2*k,S/2+r+2*k,S/2+r+2*k],fill=AMBER)
    d.ellipse([S/2-r,S/2-r,S/2+r,S/2+r],fill=INK)
    im.alpha_composite(ring(True))
    return im.resize((size,size),Image.LANCZOS)

def glow(w,h):
    bg=Image.new("RGB",(w,h),GROUND)
    g=Image.new("L",(w,h),0); ImageDraw.Draw(g).ellipse([w*0.1,-h*0.35,w*0.9,h*0.25],fill=70)
    g=g.filter(ImageFilter.GaussianBlur(w*0.12))
    bg.paste(Image.new("RGB",(w,h),AMBER),(0,0),g)
    return bg

def rounded(im,r):
    m=Image.new("L",im.size,0); ImageDraw.Draw(m).rounded_rectangle([0,0,im.size[0]-1,im.size[1]-1],r,fill=255)
    out=Image.new("RGBA",im.size,(0,0,0,0)); out.paste(im,(0,0),m); return out

def wrap(d,text,font,maxw):
    words=text.split(); lines=[]; cur=""
    for w in words:
        t=(cur+" "+w).strip()
        if d.textlength(t,font=font)<=maxw: cur=t
        else: lines.append(cur); cur=w
    lines.append(cur); return lines

def framed(src,title,sub,out):
    W,H=1080,1920
    bg=glow(W,H); d=ImageDraw.Draw(bg)
    y=110
    for line in wrap(d,title,bold(66),W-140):
        d.text((W/2,y),line,font=bold(66),fill=TEXT,anchor="ma"); y+=84
    y+=14
    for line in wrap(d,sub,reg(38),W-180):
        d.text((W/2,y),line,font=reg(38),fill=MUTED,anchor="ma"); y+=50
    shot=Image.open(src).convert("RGB")
    shot=shot.crop((0,110,shot.width,shot.height-40))  # drop status bar + gesture bar
    top=y+50; avail=H-top-0
    sw=int(W*0.80); sh=int(shot.height*sw/shot.width)
    shot=shot.resize((sw,sh),Image.LANCZOS)
    phone=Image.new("RGB",(sw+24,sh+24),(26,27,32))
    phone.paste(shot,(12,12))
    phone=rounded(phone,54)
    border=Image.new("RGBA",phone.size,(0,0,0,0)); ImageDraw.Draw(border).rounded_rectangle([0,0,phone.size[0]-1,phone.size[1]-1],54,outline=LINE+(255,),width=3)
    phone.alpha_composite(border)
    sh_layer=Image.new("RGBA",(W,H),(0,0,0,0)); ImageDraw.Draw(sh_layer).rounded_rectangle([(W-phone.width)/2,top+20,(W+phone.width)/2,top+20+phone.height],60,fill=(0,0,0,170))
    sh_layer=sh_layer.filter(ImageFilter.GaussianBlur(30))
    bg=bg.convert("RGBA"); bg.alpha_composite(sh_layer)
    bg.alpha_composite(phone,(int((W-phone.width)/2),top))  # bottom runs off the canvas on purpose
    bg.convert("RGB").save(out,optimize=True)

os.makedirs(os.path.join(OUT,"phoneScreenshots"),exist_ok=True)
items=[
 ("01-home.png","Every ask becomes its own task","Fire off a few at once. Tap any card to pick it back up."),
 ("02-task.png","Doesn't just chat. Does stuff.","Runs real commands on your phone: battery, weather, files, git."),
 ("08-browser.png","Browses the web while you watch","It opens pages, reads and clicks for you. Take over any time."),
 ("05-phone.png","Taps through your apps for you","There's always a Stop button, and it asks before it sends or pays."),
 ("06-store.png","Grab skills from pi.dev","Extensions, skills and themes for the Pi agent, one tap each."),
 ("07-terminal.png","And yes, a real terminal","bash, node, npm and git. No root, no extra apps."),
]
for i,(f,t,s) in enumerate(items,1):
    framed(os.path.join(SHOTS,f),t,s,os.path.join(OUT,"phoneScreenshots",f"{i}.png"))

saturn(512).convert("RGB").save(os.path.join(OUT,"icon.png"))
# feature graphic 1024x500
W,H=1024,500
fg=glow(W,H).convert("RGBA"); d=ImageDraw.Draw(fg)
logo=rounded(saturn(170).convert("RGB"),40); fg.alpha_composite(logo,(90,(H-170)//2))
d.text((300,165),"Saturn Agent",font=bold(76),fill=TEXT)
d.text((302,262),"An AI agent that lives on your phone.",font=reg(34),fill=MUTED)
d.text((302,312),"Open source. Bring your own AI.",font=reg(34),fill=AMBER)
fg.convert("RGB").save(os.path.join(OUT,"featureGraphic.png"))
print("ok")

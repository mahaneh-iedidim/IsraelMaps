"""mkjar.py <classes dir> <fonts dir> <icon.png> <pal> <out.jar> <out.jad>: IsraelMaps.jar + .jad"""
import sys, os, zipfile
en = '--en' in sys.argv
classes, fonts, icon, pal, out, jad = [a for a in sys.argv[1:] if a != '--en'][:6]
name = 'Israel Maps (English)' if en else 'Israel Maps'
attrs = [('MIDlet-Name', name), ('MIDlet-Vendor', 'JewishContent.org'), ('MIDlet-Version', '2.0.0'),
         ('MIDlet-1', '%s,/icon.png,mp.Maps' % name), ('MicroEdition-Configuration', 'CLDC-1.1'),
         ('MicroEdition-Profile', 'MIDP-2.0'),
         ('MIDlet-Permissions', 'javax.microedition.io.Connector.file.read'),
         ('MIDlet-Permissions-Opt', 'javax.microedition.location.Location'),
         ('MIDlet-Description', 'Offline maps of Israel: search, buildings, navigation (data file israel.map)')]
manifest = 'Manifest-Version: 1.0\r\n' + ''.join('%s: %s\r\n' % a for a in attrs) + '\r\n'
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr('META-INF/MANIFEST.MF', manifest.encode('ascii'))
    z.writestr('icon.png', open(icon, 'rb').read())
    z.writestr('pal', open(pal, 'rb').read())
    if en: z.writestr('lang', b'en')
    for f in sorted(os.listdir(os.path.join(classes, 'mp'))):
        z.writestr('mp/' + f, open(os.path.join(classes, 'mp', f), 'rb').read())
    for f in sorted(os.listdir(os.path.join(fonts, 'f'))):
        z.writestr('f/' + f, open(os.path.join(fonts, 'f', f), 'rb').read())
size = os.path.getsize(out)
with open(jad, 'w', encoding='utf-8', newline='\r\n') as f:
    for a in attrs: f.write('%s: %s\n' % a)
    f.write('MIDlet-Jar-URL: %s\nMIDlet-Jar-Size: %d\n' % (os.path.basename(out), size))
print('jar', out, size)

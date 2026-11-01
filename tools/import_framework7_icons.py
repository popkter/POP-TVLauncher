from pathlib import Path
import xml.etree.ElementTree as E,shutil,sys
# Usage: python3 tools/import_framework7_icons.py /path/to/framework7-icons-5.0.5/package
source=Path(sys.argv[1])
names='xmark minus_circle plus_circle arrow_up_arrow_down line_horizontal_3 info_circle gear_alt square_grid_2x2 checkmark plus tv film music_note_2 calendar cloud_sun timer shield bag mic antenna_radiowaves_left_right app play_rectangle'.split()
for name in names:
    root=E.parse(source/'svg'/(name+'.svg')).getroot()
    _,_,w,h=root.attrib['viewBox'].split()
    out=f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="{w}" android:viewportHeight="{h}">\n'
    for x in root:
        assert x.tag.endswith('path') and set(x.attrib)<= {'d','fill','fill-rule','transform'}
        transform=x.attrib.get('transform')
        if transform:
            coords=transform.removeprefix('translate(').removesuffix(')').split()
            out+=f'<group android:translateX="{coords[0]}" android:translateY="{coords[1]}">'
        out+='<path android:fillColor="#FFFFFFFF" android:pathData="'+x.attrib['d']+'" />\n'
        if transform:out+='</group>'
    out+='</vector>\n'
    Path('app/src/main/res/drawable/f7_'+name+'.xml').write_text(out)
shutil.copy(source/'LICENSE','app/src/main/assets/licenses/Framework7-Icons-MIT.txt')

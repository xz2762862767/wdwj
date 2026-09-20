#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ba Epic de BlackBox zu jian sheng ming zhuan cheng shi he ben App (com.mtstyle.fm) de xing shi。"""
import re
import sys

SRC = '/root/work/fm/tools/blackbox_components.xml'
OUT = '/root/work/fm/tools/components_final.xml'
INCLUDE = '/root/work/fm/tools/components_include.txt'


def main():
    with open(SRC, encoding='utf-8') as f:
        text = f.read()

    lines = [l.strip() for l in text.split('\n') if l.strip()]
    out = []
    for line in lines:
        # 1) diu diao Epic zi ji de tuo ke ru kou (Adb*DumpActivity)
        if 'epic.studio.blackdex' in line:
            continue
        # 2) bao ming ti huan
        line = line.replace('epic.studio.pro', '${applicationId}')
        # 3) theme zi yuan ti huan wei xi tong tou ming zhu ti
        line = line.replace('android:theme="@7F14000B"',
                            'android:theme="@android:style/Theme.Translucent.NoTitleBar"')
        # 4) process zi yuan -> :black
        line = line.replace('android:process="@7F130031"', 'android:process=":black"')
        # 5) ming ming kong jian xiu shi
        line = line.replace(' xmlns:ns0="http://schemas.android.com/apk/res/android"', '')
        line = line.replace('ns0:', 'android:')
        # 6) zi jie shu xing pai xu (shu ru wen ben you xu, zhe li zhi zuo shou wei bao zheng)
        line = re.sub(r'\s+', ' ', line).replace(' />', ' />')
        out.append('        ' + line)

    with open(OUT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out) + '\n')

    # gradle/manifest include yong de chun ming dan
    names = re.findall(r'android:name="([^"]+)"', '\n'.join(out))
    with open(INCLUDE, 'w', encoding='utf-8') as f:
        f.write('\n'.join(names) + '\n')

    kinds = {}
    for line in out:
        m = re.match(r'\s*<([a-z-]+)', line)
        if m:
            kinds[m.group(1)] = kinds.get(m.group(1), 0) + 1
    print('components:', len(out), kinds)
    print('unique names:', len(set(names)))
    print('sample:')
    for line in out[:3]:
        print(line)


if __name__ == '__main__':
    sys.exit(main())

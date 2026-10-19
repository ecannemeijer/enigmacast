# Third-party assets

## Profile avatars (`app/src/main/res/drawable-nodpi/avatar_01.webp` … `avatar_16.webp`)

Generated with DiceBear (https://www.dicebear.com, API 9.x) as 256 px PNG and converted to WebP. All styles used are **CC0 1.0** (public domain dedication), per https://www.dicebear.com/licenses/ :

| Files | Style | Artist |
|---|---|---|
| avatar_01–08 | Lorelei | remix of Lorelei by Lisa Wischofsky |
| avatar_09–12 | Notionists | remix of Notionists by Zoish |
| avatar_13–14 | Pixel Art | DiceBear |
| avatar_15–16 | Thumbs | DiceBear |

Seeds: Felix, Aneka, Milo, Luna, Oscar, Zoe, Jasper, Nova, Sam, Lily, Max, Ruby, Pixel, Byte, Sunny, Sky. No attribution is required; it is given here anyway. DiceBear's code is MIT licensed (Florian Körner).

## Sport flags (`app/src/main/res/drawable-nodpi/sport_flag_*.webp`)

From **flag-icons** by Panayiotis Lipiridis (https://github.com/lipis/flag-icons, `flags/4x3/`: `nl`, `gb-eng`, `de`, `es`, `it`, `fr`, `pt`, `br`, `eu`). Rendered from SVG to 320×240 WebP. "World" has no flag; the app draws a globe instead (the UN emblem is restricted and is not used).

```
The MIT License (MIT)

Copyright (c) 2013 Panayiotis Lipiridis

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Sport hero photos (`app/src/main/res/drawable-nodpi/sport_bg_*.webp`)

From Wikimedia Commons, all **CC0 1.0** (public domain dedication). They were cropped to 16:9, scaled to 1600×900 and converted to WebP. No attribution is required; it is given here anyway. None shows an identifiable person, club or branded stadium.

| File | Commons file | Author |
|---|---|---|
| sport_bg_aerial | https://commons.wikimedia.org/wiki/File:Drone_aerial_view_of_the_soccer_stadium_(Unsplash).jpg | Liane Metzler (via Unsplash) |
| sport_bg_evening | https://commons.wikimedia.org/wiki/File:Playing_football_in_evening_(Unsplash).jpg | Abigail Keenan (via Unsplash) |
| sport_bg_seats | https://commons.wikimedia.org/wiki/File:Olympic_stadium_munich_-_Flickr_-_markus_spiske.jpg | Markus Spiske |
| sport_bg_field | https://commons.wikimedia.org/wiki/File:Field_with_soccer_goal_near_the_forest_(Unsplash).jpg | Christian Widell (via Unsplash) |

## Competition and club emblems

Nothing is bundled. Emblems and crests are loaded at runtime from football-data.org (the API the app already uses for this data) and cached by Coil. When an emblem is missing, the app shows the flag above or a drawn badge. Competition and club names and logos are trademarks of their owners; showing them identifies the competition and does not imply endorsement.

## Glass skin font (`app/src/main/res/font/inter_*.ttf`)

**Inter** by The Inter Project Authors (https://github.com/rsms/inter), taken from google/fonts (`ofl/inter`). Static instances at weights 400, 500 and 600 (optical size 14) were made from the variable font with fontTools and subset to Latin, Latin-1, Latin Extended-A and common punctuation. The font is only used by the Glass Earth and Glass Wave skins. Licensed under the SIL Open Font License 1.1; Inter declares no Reserved Font Name.

```
Copyright 2020 The Inter Project Authors (https://github.com/rsms/inter)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## Glass skin backgrounds (`app/src/main/res/drawable-nodpi/skin_glass_earth.webp`, `skin_glass_wave.webp`)

Supplied by the developer (E. Cannemeijer) for this app: a glowing blue Earth horizon in space, and blue particle waves with a light flare. Cropped to 16:9 and converted to 1920×1080 WebP (quality 80).

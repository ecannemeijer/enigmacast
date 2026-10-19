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

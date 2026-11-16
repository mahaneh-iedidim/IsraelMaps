# מפות ישראל (IsraelMaps)

יישום מפות וניווט לטלפונים כשרים (J2ME, MIDP 2.0, CLDC 1.1) שעובד בלי אינטרנט: מפה של כל הארץ עד רמת הבניין,
חיפוש כתובות, נקודות עניין, ניווט לרכב, להליכה ולאופניים עם הוראות בעברית, ושכבת צילומי לוויין.

המאגר הזה מכיל את כל מה שצריך כדי לבנות את היישום ואת קובצי המפה מחדש, כך שכל אחד יכול לעדכן את המפות או
להמשיך את הפיתוח.

## מה יש כאן
- `src/mp` – קוד המקור של היישום (Java).
- `stub` – הצהרות של ממשקי JSR-75 (קבצים) ו-JSR-179 (מיקום), לצורך הידור בלבד.
- `tools` – כלי הבנייה (Python):
  - `osmread.py`, `mkmap.py` – בניית קובץ המפה `israel.map` מנתוני OpenStreetMap.
  - `mksat.py`, `pmtiles.py` – בניית קובץ צילומי הלוויין `israel-sat.map`.
  - `mkfonts.py`, `nikud.py`, `mkpal.py`, `mkicon.py`, `mkjar.py` – גופנים, צבעים, אייקון ואריזת ה-JAR.
  - `cldc.py`, `check.py` – הוספת StackMap לקובצי ה-class (במקום preverify) ובדיקה.
- `fonts/f` – הגופנים המוכנים (מפת סיביות שנוצרה מגופני Segoe UI של Windows).
- `build.ps1` – בניית ה-JAR.

## עדכון המפה (בלי לגעת בקוד)
1. מורידים קובץ OSM של ישראל בפורמט PBF, למשל מ-Geofabrik: `israel-and-palestine-latest.osm.pbf`.
2. מתקינים Python 3 ואת החבילה `osmium` (pyosmium): `pip install osmium`.
3. שלב ראשון, קריאת הנתונים (לוקח כמה דקות וצריך כמה ג'יגה זיכרון):
   `python -I tools/osmread.py israel-and-palestine-latest.osm.pbf osm.pkl`
4. שלב שני, בניית המפה:
   `python -I tools/mkmap.py osm.pkl israel.map`
5. מעתיקים את `israel.map` לכרטיס הזיכרון במקום הקובץ הישן.

מבנה הקובץ `israel.map` מתועד בראש הקובץ `tools/mkmap.py`, ומבנה `israel-sat.map` בראש `tools/mksat.py`.

## עדכון צילומי הלוויין
`tools/mksat.py` בונה את `israel-sat.map` מאריחי Sentinel-2 בפורמט PMTiles (נדרשים `numpy` ו-`Pillow`).
הפרטים בראש הקובץ.

## בניית היישום
דרושים: Java (JRE 8 ומעלה), מהדר ECJ (`ecj.jar`), קובץ JAR עם ממשקי MIDP 2.0 / CLDC 1.1 להידור מולו
(למשל `freej2me.jar`), ו-Python 3 עם Pillow.

```
powershell -File build.ps1 -Ecj lib\ecj.jar -Midp lib\midp.jar
```

התוצאה: `IsraelMaps.jar` ו-`IsraelMaps.jad`. עם `-En` נבנית גם המהדורה באנגלית.

## רישיון ומקורות
- הקוד: רישיון MIT (ראו `LICENSE`).
- נתוני המפה: © תורמי OpenStreetMap, רישיון ODbL. מי שמפיץ קובץ מפה חייב לציין זאת.
- צילומי הלוויין: Contains modified Copernicus Sentinel data.
- הגופנים שבתיקייה `fonts/f` נוצרו מגופני Segoe UI של Microsoft. מי שרוצה גופן אחר משנה את `tools/mkfonts.py`.

---

**English:** an offline map and navigation MIDlet (J2ME) for Israel. `tools/osmread.py` + `tools/mkmap.py` build the
map file from an OpenStreetMap PBF extract; the file formats are documented at the top of the tools. Code: MIT.

"""entr.py <src dir>: wraps the Hebrew interface literals of the maps app in L("he", "en") (one-time source patch)"""
import sys, os, re
src = sys.argv[1]
T = {
 "אין דרך ליד נקודת המוצא": "No road near the starting point", "אין דרך ליד היעד": "No road near the destination",
 "בוטל": "Cancelled", "לא נמצא מסלול": "No route found", "אין מספיק זיכרון למסלול כזה": "Not enough memory for this route",
 "שגיאה בקריאת המפה": "Error reading the map",
 "מחפש את קובץ המפה...": "Looking for the map file...",
 "הטלפון לא תומך בגישה לקבצים (JSR-75), ולכן אי אפשר לקרוא את קובץ המפה.": "This phone has no file access (JSR-75), so the map file cannot be read.",
 "טוען...": "Loading...", "תפריט": "Menu", "סיום": "End", "יציאה": "Exit", "בעוד ": "In ", "נותרו ": "Left ",
 "אין פריטים": "No items", "בחר": "Select", "חזרה": "Back", "אישור": "OK", "רגע...": "Please wait", "ביטול": "Cancel",
 "חיפוש כתובת או מקום": "Search address or place", "לדוגמה: הרצל 12 חולון": "e.g. הרצל 12 חולון",
 "* מחק": "* delete", "0 רווח": "0 space", "חפש": "Search", "מחק": "Delete",
 "מחפש...": "Searching...", "מחשב מסלול...": "Calculating route...", "מחפש בסביבה...": "Searching nearby...", "פותח את המפה...": "Opening the map...",
 "ניווט": "Navigation", "קובץ המפה": "Map file", "לא ניתן לפתוח את הקובץ. ודאו שזה קובץ israel.map שלם.": "The file cannot be opened. Make sure it is a complete israel.map file.",
 "שגיאה": "Error", "אין מספיק זיכרון.": "Not enough memory.", "קריאת קובץ המפה נכשלה.": "Reading the map file failed.",
 "חיפוש": "Search", "לא נמצאו תוצאות. נסו שם קצר יותר, בלי מילים כמו 'רחוב', או שם עיר אחרי המספר.": "No results. Try a shorter name, without words like 'רחוב', or a city name after the number.",
 "כביש": "Road", "רחוב": "Street", "כתובת": "Address",
 "בית כנסת": "Synagogue", "בית חולים": "Hospital", "מרפאה": "Clinic", "בית מרקחת": "Pharmacy", "בית ספר": "School",
 "מוסד אקדמי": "College", "תחנת דלק": "Fuel", "תחנת אוטובוס": "Bus stop", "תחנת רכבת": "Train station",
 "סופרמרקט / קניון": "Supermarket / mall", "חנות": "Shop", "אוכל": "Food", "בנק / כספומט": "Bank / ATM", "דואר": "Post office",
 "משטרה / כבאות": "Police / fire", "חניון": "Parking", "מלון": "Hotel", "מקווה": "Mikveh", "תיירות ומורשת": "Tourism and heritage",
 "משרד ממשלתי": "Government office", "קהילה ותרבות": "Community and culture", "משרד": "Office", "מקום": "Place",
 "מפות ישראל": "Israel Maps", "מקש 9": "Key 9", "מה יש בסביבה": "Nearby", "בתי כנסת, מקוואות, תחנות דלק...": "Synagogues, mikvehs, fuel...",
 "המקומות שלי": "My places", "עוד לא נשמרו מקומות": "No places saved yet", "הוראות המסלול": "Route directions",
 "סיום הניווט": "End navigation", "כבה GPS": "Turn GPS off", "המיקום שלי (GPS)": "My location (GPS)", "מקש 0": "Key 0",
 "אמצעי תנועה: ": "Travel by: ", "מקש 7": "Key 7", "מקשים ועזרה": "Keys and help",
 "נווט לכאן": "Navigate here", "מהמיקום שלי": "From my location", "מנקודת המוצא שנבחרה": "From the chosen start",
 "צא מכאן": "Start from here", "נקודת מוצא לניווט": "Starting point of the route", "שמור במקומות שלי": "Save to my places",
 "הסר את הסימון": "Remove the marker",
 "עוד לא נשמרו מקומות. הזיזו את המפה למקום, לחצו 5 ובחרו 'שמור במקומות שלי'.": "No places saved yet. Move the map to a place, press 5 and choose 'Save to my places'.",
 "ואז ": "then ", "אמצעי תנועה": "Travel by", "נבחר": "Selected",
 "נקודת מוצא": "Starting point",
 "נקודת המוצא נקבעה. עכשיו חפשו את היעד או הזיזו אליו את המפה, לחצו 5 ובחרו 'נווט לכאן'.": "The starting point is set. Now search for the destination or move the map to it, press 5 and choose 'Navigate here'.",
 "הצג במפה": "Show on map", "הרשימה מלאה (60 מקומות). מחקו מקום ישן.": "The list is full (60 places). Delete an old one.",
 "נשמר במקומות שלי": "Saved to my places",
 "היעד נקבע. עכשיו הזיזו את המפה לנקודת המוצא (או חפשו אותה), לחצו 5 ובחרו 'צא מכאן'.": "The destination is set. Now move the map to the starting point (or search for it), press 5 and choose 'Start from here'.",
 "הניווט הסתיים": "Navigation ended", "נקודה במפה": "Point on the map", "בחירת קובץ המפה": "Choose the map file",
 "תיקייה": "Folder", "קובץ מפה": "Map file",
 "בטלפון הזה אין GPS שהיישום יכול להשתמש בו. אפשר לבחור נקודת מוצא בעזרת 5 ← 'צא מכאן'.": "This phone has no GPS the app can use. Choose a starting point with 5 > 'Start from here'.",
 "ה-GPS כובה": "GPS is off", "מחפש מיקום...": "Finding location...", "לא ניתן להפעיל את ה-GPS.": "The GPS cannot be started.",
 "לא נמצא קובץ המפה": "Map file not found",
 "העתיקו את הקובץ israel.map לכרטיס הזיכרון: לתיקייה הראשית, לתיקייה Maps או לתיקייה של היישום. אחר כך לחצו 5 כדי לחפש שוב, או 'בחר קובץ' כדי לבחור אותו בעצמכם.": "Copy the file israel.map to the memory card: to the main folder, to a folder named Maps or to the folder of the app. Then press 5 to look again, or 'Choose file' to pick it yourself.",
 "בחר קובץ": "Choose file",
}
for fn in ('Ui.java', 'Router.java'):
    p = os.path.join(src, fn)
    s = open(p, encoding='utf-8').read()
    n = 0
    for he, en in sorted(T.items(), key=lambda kv: -len(kv[0])):
        lit = '"%s"' % he
        rep = 'Ui.L("%s", "%s")' % (he, en.replace('"', '\\"'))
        # not inside case labels / array initialisers handled by hand, nor already wrapped
        parts = s.split(lit)
        if len(parts) > 1:
            out = parts[0]
            for k in range(1, len(parts)):
                prev = out.rstrip()
                if prev.endswith('Ui.L(') or prev.endswith('{') or (prev.endswith(',') and 'static final String[]' in out[out.rfind(';'):]):
                    out += lit + parts[k]
                else:
                    out += rep + parts[k]; n += 1
            s = out
    open(p, 'w', encoding='utf-8', newline='\n').write(s)
    print(fn, n)

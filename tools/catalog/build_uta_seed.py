"""Builds src/main/resources/seed/uta-catalog.json from UTA's online catalog.

The app reads that JSON at startup to seed UTA, its degrees, each degree's
required courses and every course's official title, hours and prerequisites.
Run this again when a new catalog year is published.

Usage (Python 3.9+, standard library only):
    python3 tools/catalog/build_uta_seed.py <download dir> <output json>

<download dir> must hold the pages listed in DEGREES, SUBJECTS and CORE_PAGE,
saved as <name>.html. Fetch them with, for example:
    curl -sSL -o computer-science-bs.html \
      https://catalog.uta.edu/engineering/computer/undergraduate/computer-science-bs/
    curl -sSL -o cse-courses.html https://catalog.uta.edu/coursedescriptions/cse/
    curl -sSL -o general-core.html \
      https://catalog.uta.edu/academicregulations/degreerequirements/generalcorerequirements/
"""
import datetime
import html
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

CATALOG = "https://catalog.uta.edu"
DEGREES = [
    # (file stem, degree name, catalog path)
    ("computer-science-bs", "Computer Science", "/engineering/computer/undergraduate/computer-science-bs/"),
    ("software-engineering-bs", "Software Engineering", "/engineering/computer/undergraduate/software-engineering-bs/"),
    ("computer-engineering-bs", "Computer Engineering", "/engineering/computer/undergraduate/computer-engineering-bs/"),
]
SUBJECTS = ["cse", "math", "phys", "ie", "econ", "engl", "coms", "univ", "engr", "biol", "chem",
            # Subjects of the core curriculum courses in CORE_AREAS. GWSS courses are on the "woms" page.
            "hist", "pols", "arch", "art", "dnce", "jour", "musi", "thea", "anth", "arab", "asl", "chin",
            "clas", "divr", "woms", "fren", "germ", "global", "grek", "ints", "kore", "latn", "ling", "mas",
            "phil", "russ", "soci", "span"]

# The university-wide core curriculum, with the approved courses for each area.
CORE_PAGE = ("general-core", "/academicregulations/degreerequirements/generalcorerequirements/")
# Degree pages say "6 hours of U.S. History" without naming courses. These areas are filled in
# from CORE_PAGE: the name the degree page uses -> the heading on the core page.
CORE_AREAS = {
    "U.S. History": "U.S. HISTORY",
    "Political Science": "GOVERNMENT/POLITICAL SCIENCE",
    "Language, Philosophy, Culture": "LANGUAGE, PHILOSOPHY AND CULTURE",
    "Creative Arts": "CREATIVE ARTS",
}

# CourseSweep's own rule, on top of the catalog's: every 4000-level (senior) course also
# needs Calculus II and Physics II.
SENIOR_PREREQUISITES = ["MATH 2425", "PHYS 1444"]
SENIOR_NOTE = "CourseSweep also requires MATH 2425 (Calculus II) and PHYS 1444 (Physics II) for every 4000-level course."

# Words kept in capitals when catalog titles (all caps) are converted to title case.
KEEP_UPPER = {"CSE", "GPU", "IOT", "SOC", "RISC", "II", "III", "IV", "U.S.", "C++", "GIS", "LGBTQ+", "USA"}
KEEP_LOWER = {"a", "an", "and", "as", "at", "for", "in", "of", "on", "or", "the", "to", "with"}


def title_case(text):
    words = text.split()
    out = []
    for i, word in enumerate(words):
        core = word.strip("():,")
        if core.upper() in KEEP_UPPER or core == "I" and i > 0:
            out.append(word.upper())
        elif i > 0 and core.lower() in KEEP_LOWER:
            out.append(word.lower())
        else:
            # Capitalize each part of "Native/Heritage" and "Hands-On".
            out.append(re.sub(r"[^/-]+", lambda m: m.group(0)[:1].upper() + m.group(0)[1:].lower(), word))
    return " ".join(out)


def clean(text):
    return " ".join(html.unescape(text).replace("\xa0", " ").split())


# ---------- Course descriptions ----------

BLOCK = re.compile(r'<div class="courseblock">(.*?)</div>', re.S)
TITLE = re.compile(r'<p class="courseblocktitle"><strong>(.*?)</strong></p>', re.S)
DESC = re.compile(r'<p class="courseblockdesc">(.*?)</p>', re.S)
# e.g. "MATH 1426. CALCULUS I. 4 Hours. (TCCN = MATH 2413)"; the Texas common course number is optional.
TITLE_PARTS = re.compile(r"^([A-Z]+) (\d{4})\.\s+(.*?)\.\s+([\d-]+) Hours?\.(?:\s*\(TCCN\s*=\s*([^)]+)\))?$")


def read_courses(folder):
    courses = {}
    for subject in SUBJECTS:
        page = (folder / f"{subject}-courses.html").read_text(encoding="utf-8")
        for block in BLOCK.findall(page):
            title_html = TITLE.search(block)
            if not title_html:
                continue
            parts = TITLE_PARTS.match(clean(re.sub(r"<[^>]+>", "", title_html.group(1))))
            if not parts:
                continue
            subj, number, title, hours, tccn = parts.groups()
            desc_html = DESC.search(block)
            desc = clean(re.sub(r"<[^>]+>", "", desc_html.group(1))) if desc_html else ""
            # The official requirement text starts at the first "Prerequisite" or "Corequisite".
            start = min([i for i in (desc.find("Prerequisite"), desc.find("Corequisite")) if i >= 0], default=-1)
            courses[f"{subj} {number}"] = {
                "code": f"{subj} {number}",
                "subject": subj,
                "number": number,
                "title": title_case(title),
                "hours": hours,
                "tccn": tccn.strip() if tccn else None,
                "prerequisites": desc[start:] if start >= 0 else None,
                "prerequisiteGroups": parse_prerequisites(desc[start:]) if start >= 0 else [],
                "concurrentOk": concurrent_codes(desc[start:]) if start >= 0 else [],
            }
    return courses


# ---------- Prerequisite structure ----------
#
# The catalog states prerequisites in English. parse_prerequisites turns that into
# "all of these groups", where each group lists the courses that can satisfy it:
#     "C or better in CSE 3318, CSE 3380 or MATH 3330, and IE 3301 or MATH 3313"
#     -> [["CSE 3318"], ["CSE 3380", "MATH 3330"], ["IE 3301", "MATH 3313"]]
# A group also holds "OTHER" when something other than a course can satisfy it,
# such as an ALEKS placement score. Only groups with exactly one course and no
# "OTHER" are certain, so only those are checked off automatically in the app.
# Review the output when the catalog changes; it is plain JSON and can be fixed by hand.

PREREQ_CODE = re.compile(r"\b[A-Z]{2,5}(?:-[A-Z]{2})? \d{4}\b")
NON_COURSE = re.compile(r"ALEKS|placement|qualifying score", re.I)
# Wording that does not change which courses are needed. Exceptions such as "student
# group" or "consent of instructor" are rare, so they do not make a course optional.
NOISE = [
    r"Prerequisites?:|Co-?requisites?:",
    r"\(?or concurrent(?:ly| enrollment)(?: in)?\.?\)?",
    r"or equivalent",
    r",?\s*or student group(?: [A-Z]+ \d{4})?",
    r"or consent of instructor",
    r"\ba?\s*(?:Grade )?[ABC] or better in(?: each of the following:?| the following:?| both)?",
    r"\b(?:each of the following:?|the following:?|both|either)\b",
]


def _groups(sentence):
    for pattern in NOISE:
        sentence = re.sub(pattern, " ", sentence, flags=re.I)
    # "(A, B or C)" lists alternatives; "X (or Y)" adds one. Either way the parentheses mean "or".
    sentence = re.sub(r"\(\s*or\s+([^()]*)\)", r" or \1", sentence)
    sentence = re.sub(r"\(([^()]*\d{4}[^()]*)\)", lambda m: " " + re.sub(r",\s*(?:or\s+)?", " or ", m.group(1)) + " ", sentence)
    groups = []
    # What is left at the top level: commas and "and" join requirements, "or" joins alternatives.
    for clause in re.split(r",\s*and\s+|\s+and\s+|,", sentence):
        codes = PREREQ_CODE.findall(clause)
        if not codes:
            continue
        # An honors section stands in for the regular course with the same number.
        codes = [c for c in codes if not (c.startswith("HONR-") and any(o[-4:] == c[-4:] and o != c for o in codes))]
        group = list(dict.fromkeys(codes)) + (["OTHER"] if NON_COURSE.search(clause) else [])
        if group not in groups:
            groups.append(group)
    return groups


def parse_prerequisites(text):
    # Some courses list different prerequisites per degree plan; keep only what every plan requires.
    parts = re.split(r"For academic plan [^,]*,", text)
    common = None
    for part in parts[1:]:
        plan = [g for sentence in part.split(". ") for g in _groups(sentence)]
        common = plan if common is None else [g for g in common if g in plan]
    groups = [g for sentence in parts[0].split(". ") for g in _groups(sentence)]
    return groups + [g for g in (common or []) if g not in groups]


CONCURRENT = re.compile(r"concurrent(?:ly| enrollment)?(\s+in\b)?", re.I)


def concurrent_codes(text):
    """The prerequisite courses the catalog lets a student take in the same term instead of before.

    "MATH 1426 (or concurrent enrollment)" and "CSE 3314 (or concurrently)" mark the course
    just before the words; "(or concurrent enrollment in) (MATH 1421, MATH 1426)" and
    "or concurrent enrollment in MATH 2425" mark the courses after them, up to the next ")".
    """
    found = []
    for match in CONCURRENT.finditer(text):
        if match.group(1):
            rest = text[match.end():]
            first = PREREQ_CODE.search(rest)
            if not first:
                continue
            close = rest.find(")", first.start())
            found += PREREQ_CODE.findall(rest[:close if close >= 0 else len(rest)])
        else:
            before = PREREQ_CODE.findall(text[:match.start()])
            if before:
                found.append(before[-1])
    return sorted({c for c in found if not c.startswith("HONR-")})


# ---------- Degree requirement tables ----------

class CourseList(HTMLParser):
    """Collects the rows of every sc_courselist table as (kind, code, text, hours)."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.row = None
        self.cell = None
        self.rows = []

    def handle_starttag(self, tag, attrs):
        cls = dict(attrs).get("class", "") or ""
        if tag == "table" and "sc_courselist" in cls:
            self.depth = 1
        elif self.depth and tag == "table":
            self.depth += 1
        elif self.depth and tag == "tr":
            self.row = {"class": cls, "cells": []}
        elif self.depth and tag == "td" and self.row is not None:
            self.cell = {"class": cls, "text": []}

    def handle_endtag(self, tag):
        if not self.depth:
            return
        if tag == "table":
            self.depth -= 1
        elif tag == "td" and self.cell is not None:
            self.row["cells"].append((self.cell["class"], clean("".join(self.cell["text"]))))
            self.cell = None
        elif tag == "tr" and self.row is not None:
            cls, cells = self.row["class"], self.row["cells"]
            self.row = None
            code = next((t for c, t in cells if "codecol" in c), "")
            hours = next((t for c, t in cells if "hourscol" in c), "")
            text = " ".join(t for c, t in cells if "codecol" not in c and "hourscol" not in c)
            if "areaheader" in cls or "areasubheader" in cls:
                self.rows.append(("header", "", text or code, hours))
            elif code and re.match(r"^(or )?[A-Z]+ \d{4}", code) and "comment" not in cls:
                self.rows.append(("or" if code.startswith("or ") else "course", code, text, hours))
            else:
                self.rows.append(("comment", "", text or code, hours))

    def handle_data(self, data):
        if self.cell is not None:
            self.cell["text"].append(data)


CODE = re.compile(r"[A-Z]+ \d{4}")
# Trailing footnote numbers and colons in headers, e.g. "Specialization (Professional Courses): 2".
FOOTNOTE = re.compile(r"[\s:]*\d?[\s:]*$")


def read_requirements(page):
    """Turns the table rows into requirement slots.

    A slot is satisfied by any one of its options; an option is a list of
    courses taken together (e.g. a lecture and its lab).
    choose_hours is set when the student picks that many hours from the options.
    """
    parser = CourseList()
    parser.feed(page)
    requirements, total_hours = [], None
    # Hours the plan requires without naming courses: core curriculum areas and free electives.
    not_listed = []
    section, choosing = None, None
    for kind, code, text, hours in parser.rows:
        if kind == "header":
            section, choosing = FOOTNOTE.sub("", text), None
        elif kind == "comment":
            if text.startswith("Total Hours"):
                total_hours = int(hours)
            elif text.startswith("In addition to the specified courses"):
                # "...must choose 6 hours of U.S. History, 6 hours of Political Science, ... in the general education core."
                for area_hours, area in re.findall(r"(\d+) hours of (.+?)(?=,? (?:and )?\d+ hours of| in the general|\.$)", text):
                    not_listed.append({"name": "Core curriculum: " + area.strip(" ,"), "hours": int(area_hours)})
            elif "electives sufficient to complete" in text and hours:
                not_listed.append({"name": "Free electives", "hours": int(hours)})
            elif text.startswith("For ") and text.endswith("select:"):
                section, choosing = "Core curriculum: " + text[4:-len(" select:")], None
            elif text.startswith("Select") and hours and "electives sufficient" not in text:
                choosing = {"section": section, "chooseHours": int(hours), "note": text, "options": []}
                requirements.append(choosing)
            elif text.endswith("Foundations") or text == "Technical Electives":
                section, choosing = text, None
            else:
                choosing = None if not text.startswith("Select") else choosing
        else:
            option = CODE.findall(code)
            if choosing is not None:
                choosing["options"].append(option)
            elif kind == "or" and requirements:
                requirements[-1]["options"].append(option)
            else:
                requirements.append({"section": section, "chooseHours": None, "note": None, "options": [option]})
    return requirements, total_hours, not_listed


# ---------- General core curriculum ----------

AREA = re.compile(r"<h3>(.*?)</h3>(.*?)(?=<h3>|$)", re.S)
ROW = re.compile(r"<tr([^>]*)>(.*?)</tr>", re.S)
# The course a row links to. Cross-listed courses ("DIVR/GWSS 2315") link to one code.
ROW_CODE = re.compile(r"showCourse\(this, '([A-Z]+ \d{4})'\)")


def read_core_areas(page):
    """Each core area's slots, keyed by its heading: a list of slots, each a list of course codes.

    A "Select one/two of the following" area is one slot holding every listed course. An area
    that names its courses outright (Government) has one slot per course, and a row starting
    with "or" (HIST 1331 or HIST 1301) is an alternative in the slot before it.
    """
    areas = {}
    for heading, body in AREA.findall(page):
        rows = []
        for attrs, row in ROW.findall(body):
            code = ROW_CODE.search(row)
            if code:
                # "or HIST 1331" rows carry the orclass class.
                rows.append((code.group(1), "orclass" in attrs))
        # "Select ..." with no "or" rows: the student picks from one list.
        one_list = "Select" in body and not any(is_or for _, is_or in rows)
        slots = []
        for code, is_or in rows:
            if slots and (is_or or one_list):
                slots[-1].append(code)
            else:
                slots.append([code])
        areas[clean(heading)] = slots
    return areas


def add_core_requirements(requirements, not_listed, core_areas, courses):
    """Turns the degree's unnamed core hours into requirements listing the approved courses.

    "Select two" in U.S. History becomes two slots (HIST 1301 or 1331, then HIST 1302 or 1332);
    "select one" areas become a choose-N-hours group. Areas not in CORE_AREAS stay in not_listed.
    """
    added, kept = [], []
    for item in not_listed:
        area = item["name"].removeprefix("Core curriculum: ")
        slots = core_areas.get(CORE_AREAS.get(area, ""))
        if not slots:
            kept.append(item)
            continue
        section = item["name"]
        if len(slots) == 1 and len(slots[0]) > 1:
            added.append({"section": section, "chooseHours": item["hours"], "note": "Select one of the following:",
                          "options": [[code] for code in slots[0]]})
        else:
            for slot in slots:
                added.append({"section": section, "chooseHours": None, "note": None,
                              "options": [[code] for code in slot]})
    # Keep the core curriculum together: put the new areas after the last core requirement.
    last_core = max((i for i, r in enumerate(requirements) if (r["section"] or "").startswith("Core curriculum")),
                    default=-1)
    return requirements[:last_core + 1] + added + requirements[last_core + 1:], kept


def add_senior_prerequisites(courses):
    for course in courses.values():
        if course["number"].startswith("4"):
            for code in SENIOR_PREREQUISITES:
                if [code] not in course["prerequisiteGroups"]:
                    course["prerequisiteGroups"].append([code])
            course["prerequisites"] = ((course["prerequisites"] or "") + " " + SENIOR_NOTE).strip()


def main(folder, output):
    folder = Path(folder)
    courses = read_courses(folder)
    add_senior_prerequisites(courses)
    core_areas = read_core_areas((folder / f"{CORE_PAGE[0]}.html").read_text(encoding="utf-8"))
    degrees, used = [], set()
    for stem, name, path in DEGREES:
        requirements, total, not_listed = read_requirements((folder / f"{stem}.html").read_text(encoding="utf-8"))
        requirements, not_listed = add_core_requirements(requirements, not_listed, core_areas, courses)
        for requirement in requirements:
            for option in requirement["options"]:
                used.update(option)
        degrees.append({"name": name, "award": "Bachelor of Science", "totalHours": total,
                        "catalogUrl": CATALOG + path, "requirements": requirements,
                        "notListedHours": not_listed})
    missing = sorted(used - courses.keys())
    if missing:
        sys.exit(f"Courses used by a degree but missing from the course pages: {missing}")
    seed = {
        "source": {
            "catalog": "UTA University Catalog 2026-2027",
            "retrieved": datetime.date.today().isoformat(),
            "degreePages": [CATALOG + path for _, _, path in DEGREES],
            "coursePages": [f"{CATALOG}/coursedescriptions/{s}/" for s in SUBJECTS],
            "corePage": CATALOG + CORE_PAGE[1],
        },
        "college": {"name": "The University of Texas at Arlington", "shortName": "UTA"},
        "degrees": degrees,
        "courses": [courses[code] for code in sorted(used)],
    }
    Path(output).write_text(json.dumps(seed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{len(degrees)} degrees, {len(used)} courses -> {output}")


if __name__ == "__main__":
    main(*sys.argv[1:3])

"""Builds src/main/resources/seed/uta-catalog.json from UTA's online catalog.

The app reads that JSON at startup to seed UTA, its degrees, each degree's
required courses and every course's official title, hours and prerequisites.
Run this again when a new catalog year is published.

Usage (Python 3.9+, standard library only):
    python3 tools/catalog/build_uta_seed.py <download dir> <output json>

<download dir> must hold the pages listed in PAGES and SUBJECTS, saved as
<name>.html. Fetch them with, for example:
    curl -sSL -o computer-science-bs.html \
      https://catalog.uta.edu/engineering/computer/undergraduate/computer-science-bs/
    curl -sSL -o cse-courses.html https://catalog.uta.edu/coursedescriptions/cse/
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
SUBJECTS = ["cse", "math", "phys", "ie", "econ", "engl", "coms", "univ", "engr", "biol", "chem"]

# Words kept in capitals when catalog titles (all caps) are converted to title case.
KEEP_UPPER = {"CSE", "GPU", "IOT", "SOC", "RISC", "II", "III", "IV", "U.S.", "C++", "GIS"}
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
            out.append("-".join(part[:1].upper() + part[1:].lower() for part in word.split("-")))
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
            }
    return courses


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
    section, choosing = None, None
    for kind, code, text, hours in parser.rows:
        if kind == "header":
            section, choosing = FOOTNOTE.sub("", text), None
        elif kind == "comment":
            if text.startswith("Total Hours"):
                total_hours = int(hours)
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
    return requirements, total_hours


def main(folder, output):
    folder = Path(folder)
    courses = read_courses(folder)
    degrees, used = [], set()
    for stem, name, path in DEGREES:
        requirements, total = read_requirements((folder / f"{stem}.html").read_text(encoding="utf-8"))
        for requirement in requirements:
            for option in requirement["options"]:
                used.update(option)
        degrees.append({"name": name, "award": "Bachelor of Science", "totalHours": total,
                        "catalogUrl": CATALOG + path, "requirements": requirements})
    missing = sorted(used - courses.keys())
    if missing:
        sys.exit(f"Courses used by a degree but missing from the course pages: {missing}")
    seed = {
        "source": {
            "catalog": "UTA University Catalog 2026-2027",
            "retrieved": datetime.date.today().isoformat(),
            "degreePages": [CATALOG + path for _, _, path in DEGREES],
            "coursePages": [f"{CATALOG}/coursedescriptions/{s}/" for s in SUBJECTS],
        },
        "college": {"name": "The University of Texas at Arlington", "shortName": "UTA"},
        "degrees": degrees,
        "courses": [courses[code] for code in sorted(used)],
    }
    Path(output).write_text(json.dumps(seed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{len(degrees)} degrees, {len(used)} courses -> {output}")


if __name__ == "__main__":
    main(*sys.argv[1:3])

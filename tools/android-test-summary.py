"""Print bounded JUnit failure details to the CI log; never replace Gradle's exit status."""
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(root):
    shown = 0
    for path in sorted(root.glob('android/app/build/test-results/test*UnitTest/TEST-*.xml')):
        try:
            suite = ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            print('Could not parse JUnit report:', path.name)
            continue
        for case in suite.iter('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                if shown >= 8:
                    print('Further failures omitted; download Android-build-reports for all reports.')
                    return
                shown += 1
                print('FAILED:', case.get('classname', ''), '>', case.get('name', ''))
                detail = '\n'.join((failure.text or failure.get('message', '')).splitlines()[:24])[:4000]
                print(''.join(c for c in detail if c in '\n\t' or c.isprintable()))
    if not shown:
        print('No JUnit failures found in available XML reports (reports may not exist yet).')


if __name__ == '__main__':
    summarize(Path(__file__).resolve().parents[1])

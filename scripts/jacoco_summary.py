import argparse
import csv
import os
import sys


def get_percentage(num: int, total: int) -> str:
    if total == 0:
        return "N/A"
    p = 100 * num / total
    return f"{p:.1f}%"


def main() -> int:
    arg_parser = argparse.ArgumentParser()
    arg_parser.add_argument(
        "--file",
        "-f",
        default="target/site/jacoco/jacoco.csv",
        help="input jacoco csv file",
    )
    args = arg_parser.parse_args()

    if not os.path.isfile(args.file):
        print(f"File {args.file} is not valid", file=sys.stderr)
        return 1

    with open(args.file) as f:
        csv_rows = list(csv.DictReader(f))

    data_header_row = ["Class", "Line Coverage", "Branch Coverage"]

    total_lines_covered = 0
    total_lines_missed = 0
    total_branches_covered = 0
    total_branches_missed = 0

    data_rows = []

    for csv_row in csv_rows:
        lines_covered = int(csv_row["LINE_COVERED"])
        lines_missed = int(csv_row["LINE_MISSED"])
        lines = lines_covered + lines_missed
        branches_covered = int(csv_row["BRANCH_COVERED"])
        branches_missed = int(csv_row["BRANCH_MISSED"])
        branches = branches_covered + branches_missed

        lines_percentage = get_percentage(lines_covered, lines)
        branches_percentage = get_percentage(branches_covered, branches)

        total_lines_covered += lines_covered
        total_lines_missed += lines_missed
        total_branches_covered += branches_covered
        total_branches_missed += branches_missed

        package = csv_row["PACKAGE"]
        clazz = csv_row["CLASS"]
        data_rows.append(
            [
                f"`{package}.{clazz}`",
                f"{lines_percentage} ({lines_covered}/{lines})",
                f"{branches_percentage} ({branches_covered}/{branches})",
            ]
        )

    total_lines = total_lines_covered + total_lines_missed
    total_lines_percentage = get_percentage(total_lines_covered, total_lines)
    total_branches = total_branches_covered + total_branches_missed
    total_branches_percentage = get_percentage(total_branches_covered, total_branches)
    data_rows.append(
        [
            "**Total**",
            f"**{total_lines_percentage} ({total_lines_covered}/{total_lines})**",
            f"**{total_branches_percentage} ({total_branches_covered}/{total_branches})**",
        ]
    )

    num_cols = len(data_header_row)

    max_widths = [len(cell) for cell in data_header_row]
    for data_row in data_rows:
        widths = [len(cell) for cell in data_row]
        for i in range(num_cols):
            max_widths[i] = max(max_widths[i], widths[i])

    print("### Code Coverage\n")
    row = []
    row.append("")
    for i in range(num_cols):
        row.append(data_header_row[i].center(max_widths[i]))
    row.append("")
    print(" | ".join(row))

    row = [
        "",
        f"{'-' * max_widths[0]}",
        f"{'-' * (max_widths[1] - 1)}:",
        f"{'-' * (max_widths[2] - 1)}:",
        "",
    ]
    print(" | ".join(row))

    for data_row in data_rows:
        row = [
            "",
            data_row[0].ljust(max_widths[0]),
            data_row[1].rjust(max_widths[1]),
            data_row[2].rjust(max_widths[2]),
            "",
        ]
        print(" | ".join(row))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

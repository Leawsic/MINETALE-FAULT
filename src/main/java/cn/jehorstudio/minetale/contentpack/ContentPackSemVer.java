package cn.jehorstudio.minetale.contentpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 仅实现 Content Pack manifest 接受的 SemVer 子集与范围语法。
public record ContentPackSemVer(int major, int minor, int patch, String prerelease) implements Comparable<ContentPackSemVer> {
    private static final Pattern VERSION = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");
    private static final Pattern COMPARATOR = Pattern.compile("^(<=|>=|<|>|=)?(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)$");

    public ContentPackSemVer {
        if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("SemVer 数字不能为负。");
        prerelease = prerelease == null ? "" : prerelease;
    }

    public static ContentPackSemVer parse(String text) {
        Matcher matcher = VERSION.matcher(Objects.requireNonNull(text, "text"));
        if (!matcher.matches()) throw new IllegalArgumentException("不是有效 SemVer：" + text);
        return new ContentPackSemVer(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), matcher.group(4));
    }

    public static boolean satisfies(ContentPackSemVer version, String range) {
        String normalized = Objects.requireNonNull(range, "range").trim();
        if (normalized.equals("*") || normalized.isEmpty()) return true;
        for (String alternative : normalized.split("\\s*\\|\\|\\s*")) if (satisfiesAll(version, alternative.trim())) return true;
        return false;
    }

    private static boolean satisfiesAll(ContentPackSemVer version, String range) {
        if (range.startsWith("^")) {
            ContentPackSemVer floor = parse(range.substring(1));
            ContentPackSemVer ceiling = floor.major > 0
                    ? new ContentPackSemVer(floor.major + 1, 0, 0, "")
                    : floor.minor > 0 ? new ContentPackSemVer(0, floor.minor + 1, 0, "") : new ContentPackSemVer(0, 0, floor.patch + 1, "");
            return version.compareTo(floor) >= 0 && version.compareTo(ceiling) < 0;
        }
        if (range.startsWith("~")) {
            ContentPackSemVer floor = parse(range.substring(1));
            ContentPackSemVer ceiling = new ContentPackSemVer(floor.major, floor.minor + 1, 0, "");
            return version.compareTo(floor) >= 0 && version.compareTo(ceiling) < 0;
        }
        List<String> terms = new ArrayList<>(List.of(range.split("\\s+")));
        for (String term : terms) {
            if (term.isBlank()) continue;
            Matcher matcher = COMPARATOR.matcher(term);
            if (!matcher.matches()) throw new IllegalArgumentException("不支持的 SemVer 范围：" + range);
            String operator = matcher.group(1) == null ? "=" : matcher.group(1);
            int comparison = version.compareTo(parse(matcher.group(2)));
            boolean accepted = switch (operator) {
                case "=" -> comparison == 0;
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                default -> false;
            };
            if (!accepted) return false;
        }
        return true;
    }

    @Override
    public int compareTo(ContentPackSemVer other) {
        int comparison = Integer.compare(this.major, other.major);
        if (comparison == 0) comparison = Integer.compare(this.minor, other.minor);
        if (comparison == 0) comparison = Integer.compare(this.patch, other.patch);
        if (comparison != 0) return comparison;
        if (this.prerelease.isEmpty()) return other.prerelease.isEmpty() ? 0 : 1;
        if (other.prerelease.isEmpty()) return -1;
        return comparePrerelease(this.prerelease, other.prerelease);
    }

    private static int comparePrerelease(String left, String right) {
        String[] leftParts = left.split("\\."); String[] rightParts = right.split("\\.");
        for (int index = 0; index < Math.min(leftParts.length, rightParts.length); index++) {
            String a = leftParts[index]; String b = rightParts[index];
            boolean aNumeric = a.chars().allMatch(Character::isDigit); boolean bNumeric = b.chars().allMatch(Character::isDigit);
            int comparison = aNumeric && bNumeric ? Integer.compare(Integer.parseInt(a), Integer.parseInt(b))
                    : aNumeric != bNumeric ? (aNumeric ? -1 : 1) : a.compareTo(b);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    @Override public String toString() { return major + "." + minor + "." + patch + (prerelease.isEmpty() ? "" : "-" + prerelease); }
}

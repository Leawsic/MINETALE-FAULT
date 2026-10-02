package cn.jehorstudio.minetale.narrative.client;

import com.ibm.icu.text.BreakIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 按 Unicode 字素簇推进打字机，组合字符与 emoji 作为整体显示。
final class DialogueText {
    private DialogueText() {
    }

    static List<String> graphemes(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        List<String> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            result.add(text.substring(start, end));
        }
        return List.copyOf(result);
    }
}

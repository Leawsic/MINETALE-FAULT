package cn.jehorstudio.minetale.dimension.worldgen.asset.matching;

import java.util.List;

public record ConnectorMatchResult(
        List<ConnectorMatch> matches,
        List<String> diagnostics
) {
    public ConnectorMatchResult {
        matches = List.copyOf(matches == null ? List.of() : matches);
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }
}
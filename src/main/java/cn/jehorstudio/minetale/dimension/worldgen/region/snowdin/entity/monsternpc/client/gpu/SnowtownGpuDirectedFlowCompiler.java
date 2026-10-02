package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import net.minecraft.util.Mth;

import java.util.Arrays;

import static cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.CELL_COUNT;
import static cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.MAX_STEP_HEIGHT;
import static cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownGpuCrowdStaticField.SIZE;

// 将可走面确定性编译成一个主环及最终汇入该环的有向支流。
final class SnowtownGpuDirectedFlowCompiler {
    private static final float MIN_MAIN_STREAM_CLEARANCE = 1.25F;
    private static final float MAX_MAIN_STREAM_CLEARANCE = 3.25F;
    private static final float MAIN_STREAM_CLEARANCE_BAND = 1.0F;
    private static final int MACRO_CELL_SIZE = 2;
    private static final int MACRO_EDGE = SIZE / MACRO_CELL_SIZE;
    private static final int MACRO_CELL_COUNT = MACRO_EDGE * MACRO_EDGE;
    private static final int[] CARDINAL_X = {1, -1, 0, 0};
    private static final int[] CARDINAL_Z = {0, 0, 1, -1};

    private SnowtownGpuDirectedFlowCompiler() {
    }

    // 稀疏宏格经最短连接树组成主环，其余可走格再由多源 BFS 定向汇入。
    static DirectedFlow compile(
            boolean[] walkable,
            float[] heights,
            float[] clearance
    ) {
        if (walkable.length != CELL_COUNT
                || heights.length != CELL_COUNT
                || clearance.length != CELL_COUNT) {
            throw new IllegalArgumentException("Snowtown GPU 有向流场输入尺寸不匹配");
        }

        int[] componentByCell = labelComponents(walkable, heights);
        int componentCount = 0;
        for (int component : componentByCell) {
            componentCount = Math.max(componentCount, component + 1);
        }
        int[] parityCounts = new int[componentCount * 4];
        float[] minimumMacroClearance = new float[componentCount * 4];
        Arrays.fill(minimumMacroClearance, Float.POSITIVE_INFINITY);
        for (int parity = 0; parity < 4; parity++) {
            int offsetX = parity & 1;
            int offsetZ = parity >>> 1;
            for (int z = offsetZ; z + 1 < SIZE; z += MACRO_CELL_SIZE) {
                for (int x = offsetX; x + 1 < SIZE; x += MACRO_CELL_SIZE) {
                    if (!validMacroBlock(
                            walkable, heights, clearance, componentByCell, x, z)) {
                        continue;
                    }
                    int component = componentByCell[index(x, z)];
                    int slot = component * 4 + parity;
                    parityCounts[slot]++;
                    minimumMacroClearance[slot] = Math.min(
                            minimumMacroClearance[slot],
                            macroBlockClearance(clearance, x, z));
                }
            }
        }

        int[] bestParityByComponent = new int[componentCount];
        Arrays.fill(bestParityByComponent, -1);
        for (int component = 0; component < componentCount; component++) {
            int bestCount = 0;
            for (int parity = 0; parity < 4; parity++) {
                int count = parityCounts[component * 4 + parity];
                if (count > bestCount) {
                    bestCount = count;
                    bestParityByComponent[component] = parity;
                }
            }
        }

        boolean[] candidateMacroNodes = new boolean[4 * MACRO_CELL_COUNT];
        boolean[] preferredMacroNodes = new boolean[4 * MACRO_CELL_COUNT];
        float[] preferredThreshold = new float[componentCount];
        for (int component = 0; component < componentCount; component++) {
            int parity = bestParityByComponent[component];
            if (parity < 0) {
                continue;
            }
            float minimum = minimumMacroClearance[component * 4 + parity];
            preferredThreshold[component] = Math.min(
                    MAX_MAIN_STREAM_CLEARANCE,
                    minimum + MAIN_STREAM_CLEARANCE_BAND);
        }
        for (int parity = 0; parity < 4; parity++) {
            int offsetX = parity & 1;
            int offsetZ = parity >>> 1;
            for (int z = offsetZ; z + 1 < SIZE; z += MACRO_CELL_SIZE) {
                for (int x = offsetX; x + 1 < SIZE; x += MACRO_CELL_SIZE) {
                    int component = componentByCell[index(x, z)];
                    if (component < 0
                            || bestParityByComponent[component] != parity
                            || !validMacroBlock(
                                    walkable,
                                    heights,
                                    clearance,
                                    componentByCell,
                                    x,
                                    z)) {
                        continue;
                    }
                    int node = macroNode(
                            parity,
                            (x - offsetX) / MACRO_CELL_SIZE,
                            (z - offsetZ) / MACRO_CELL_SIZE);
                    candidateMacroNodes[node] = true;
                    preferredMacroNodes[node] = macroBlockClearance(clearance, x, z)
                            <= preferredThreshold[component];
                }
            }
        }
        boolean[] macroNodes = connectPreferredMacroNodes(
                candidateMacroNodes,
                preferredMacroNodes,
                walkable,
                heights);
        int[] successor = new int[CELL_COUNT];
        Arrays.fill(successor, -1);
        byte[] kind = new byte[CELL_COUNT];
        boolean[] componentHasMainStream = new boolean[componentCount];
        for (int parity = 0; parity < 4; parity++) {
            int offsetX = parity & 1;
            int offsetZ = parity >>> 1;
            for (int z = offsetZ; z + 1 < SIZE; z += MACRO_CELL_SIZE) {
                for (int x = offsetX; x + 1 < SIZE; x += MACRO_CELL_SIZE) {
                    int node = macroNode(
                            parity,
                            (x - offsetX) / MACRO_CELL_SIZE,
                            (z - offsetZ) / MACRO_CELL_SIZE);
                    if (!macroNodes[node]) {
                        continue;
                    }
                    installClockwiseMacroCycle(successor, kind, x, z);
                    int component = componentByCell[index(x, z)];
                    componentHasMainStream[component] = true;
                }
            }
        }

        mergeMacroCycles(
                macroNodes,
                componentByCell,
                componentCount,
                walkable,
                heights,
                successor);
        int circulationCount = keepLargestCirculationPerComponent(
                componentByCell, successor, kind, componentCount);
        circulationCount += installFallbackCycles(
                componentByCell,
                componentHasMainStream,
                walkable,
                heights,
                clearance,
                successor,
                kind);

        int[] queue = new int[CELL_COUNT];
        int queueHead = 0;
        int queueTail = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (kind[cell] == 2) {
                queue[queueTail++] = cell;
            }
        }
        while (queueHead < queueTail) {
            int current = queue[queueHead++];
            int currentX = current % SIZE;
            int currentZ = current / SIZE;
            for (int direction = 0; direction < CARDINAL_X.length; direction++) {
                int nextX = currentX + CARDINAL_X[direction];
                int nextZ = currentZ + CARDINAL_Z[direction];
                if (!canTraverse(
                        walkable, heights, currentX, currentZ, nextX, nextZ)) {
                    continue;
                }
                int next = index(nextX, nextZ);
                if (successor[next] >= 0) {
                    continue;
                }
                successor[next] = current;
                kind[next] = 1;
                queue[queueTail++] = next;
            }
        }

        float[] flowX = new float[CELL_COUNT];
        float[] flowZ = new float[CELL_COUNT];
        int mainStreamCellCount = 0;
        int tributaryCellCount = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int next = successor[cell];
            if (next < 0) {
                continue;
            }
            float deltaX = next % SIZE - cell % SIZE;
            float deltaZ = next / SIZE - cell / SIZE;
            float length = Mth.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            if (length > 0.0F) {
                flowX[cell] = deltaX / length;
                flowZ[cell] = deltaZ / length;
            }
            if (kind[cell] == 2) {
                mainStreamCellCount++;
            } else if (kind[cell] == 1) {
                tributaryCellCount++;
            }
        }
        return new DirectedFlow(
                flowX,
                flowZ,
                kind,
                successor,
                mainStreamCellCount,
                tributaryCellCount,
                circulationCount);
    }

    private static int[] labelComponents(boolean[] walkable, float[] heights) {
        int[] componentByCell = new int[CELL_COUNT];
        Arrays.fill(componentByCell, -1);
        int[] queue = new int[CELL_COUNT];
        int component = 0;
        for (int start = 0; start < CELL_COUNT; start++) {
            if (!walkable[start] || componentByCell[start] >= 0) {
                continue;
            }
            int queueHead = 0;
            int queueTail = 0;
            queue[queueTail++] = start;
            componentByCell[start] = component;
            while (queueHead < queueTail) {
                int current = queue[queueHead++];
                int x = current % SIZE;
                int z = current / SIZE;
                for (int direction = 0; direction < CARDINAL_X.length; direction++) {
                    int nextX = x + CARDINAL_X[direction];
                    int nextZ = z + CARDINAL_Z[direction];
                    if (!canTraverse(walkable, heights, x, z, nextX, nextZ)) {
                        continue;
                    }
                    int next = index(nextX, nextZ);
                    if (componentByCell[next] >= 0) {
                        continue;
                    }
                    componentByCell[next] = component;
                    queue[queueTail++] = next;
                }
            }
            component++;
        }
        return componentByCell;
    }

    private static boolean validMacroBlock(
            boolean[] walkable,
            float[] heights,
            float[] clearance,
            int[] componentByCell,
            int x,
            int z
    ) {
        int topLeft = index(x, z);
        int topRight = index(x + 1, z);
        int bottomRight = index(x + 1, z + 1);
        int bottomLeft = index(x, z + 1);
        int component = componentByCell[topLeft];
        return component >= 0
                && componentByCell[topRight] == component
                && componentByCell[bottomRight] == component
                && componentByCell[bottomLeft] == component
                && clearance[topLeft] >= MIN_MAIN_STREAM_CLEARANCE
                && clearance[topRight] >= MIN_MAIN_STREAM_CLEARANCE
                && clearance[bottomRight] >= MIN_MAIN_STREAM_CLEARANCE
                && clearance[bottomLeft] >= MIN_MAIN_STREAM_CLEARANCE
                && canTraverse(walkable, heights, x, z, x + 1, z)
                && canTraverse(walkable, heights, x + 1, z, x + 1, z + 1)
                && canTraverse(walkable, heights, x + 1, z + 1, x, z + 1)
                && canTraverse(walkable, heights, x, z + 1, x, z);
    }

    private static float macroBlockClearance(float[] clearance, int x, int z) {
        return Math.max(
                Math.max(clearance[index(x, z)], clearance[index(x + 1, z)]),
                Math.max(clearance[index(x, z + 1)], clearance[index(x + 1, z + 1)]));
    }

    // 只补入连接净空带所需的 BFS 树宏格，以防整片广场被误划入主环。
    private static boolean[] connectPreferredMacroNodes(
            boolean[] candidates,
            boolean[] preferred,
            boolean[] walkable,
            float[] heights
    ) {
        boolean[] selected = preferred.clone();
        int[] owner = new int[candidates.length];
        int[] pathParent = new int[candidates.length];
        Arrays.fill(owner, -1);
        Arrays.fill(pathParent, -1);
        int[] queue = new int[candidates.length];
        int queueTail = 0;
        int ownerCount = 0;
        for (int root = 0; root < preferred.length; root++) {
            if (!preferred[root] || owner[root] >= 0) {
                continue;
            }
            int queueHead = 0;
            int componentTail = 0;
            queue[componentTail++] = root;
            owner[root] = ownerCount;
            pathParent[root] = root;
            while (queueHead < componentTail) {
                int current = queue[queueHead++];
                int parity = current / MACRO_CELL_COUNT;
                int local = current % MACRO_CELL_COUNT;
                int macroX = local % MACRO_EDGE;
                int macroZ = local / MACRO_EDGE;
                for (int direction = 0; direction < CARDINAL_X.length; direction++) {
                    int nextX = macroX + CARDINAL_X[direction];
                    int nextZ = macroZ + CARDINAL_Z[direction];
                    if (nextX < 0 || nextX >= MACRO_EDGE
                            || nextZ < 0 || nextZ >= MACRO_EDGE) {
                        continue;
                    }
                    int next = macroNode(parity, nextX, nextZ);
                    if (owner[next] >= 0
                            || !preferred[next]
                            || !canJoinMacroBlocks(
                                    parity,
                                    macroX,
                                    macroZ,
                                    nextX,
                                    nextZ,
                                    walkable,
                                    heights)) {
                        continue;
                    }
                    owner[next] = ownerCount;
                    pathParent[next] = next;
                    queue[componentTail++] = next;
                }
            }
            ownerCount++;
        }

        int[] ownerParent = new int[ownerCount];
        for (int ownerId = 0; ownerId < ownerCount; ownerId++) {
            ownerParent[ownerId] = ownerId;
        }
        for (int node = 0; node < preferred.length; node++) {
            if (preferred[node]) {
                queue[queueTail++] = node;
            }
        }
        int queueHead = 0;
        while (queueHead < queueTail) {
            int current = queue[queueHead++];
            int parity = current / MACRO_CELL_COUNT;
            int local = current % MACRO_CELL_COUNT;
            int macroX = local % MACRO_EDGE;
            int macroZ = local / MACRO_EDGE;
            for (int direction = 0; direction < CARDINAL_X.length; direction++) {
                int nextX = macroX + CARDINAL_X[direction];
                int nextZ = macroZ + CARDINAL_Z[direction];
                if (nextX < 0 || nextX >= MACRO_EDGE
                        || nextZ < 0 || nextZ >= MACRO_EDGE) {
                    continue;
                }
                int next = macroNode(parity, nextX, nextZ);
                if (!candidates[next]
                        || !canJoinMacroBlocks(
                                parity,
                                macroX,
                                macroZ,
                                nextX,
                                nextZ,
                                walkable,
                                heights)) {
                    continue;
                }
                if (owner[next] < 0) {
                    owner[next] = owner[current];
                    pathParent[next] = current;
                    queue[queueTail++] = next;
                    continue;
                }
                int firstRoot = macroRoot(ownerParent, owner[current]);
                int secondRoot = macroRoot(ownerParent, owner[next]);
                if (firstRoot == secondRoot) {
                    continue;
                }
                ownerParent[secondRoot] = firstRoot;
                selectMacroPath(selected, pathParent, current);
                selectMacroPath(selected, pathParent, next);
            }
        }
        return selected;
    }

    private static void selectMacroPath(
            boolean[] selected,
            int[] pathParent,
            int start
    ) {
        int current = start;
        while (!selected[current]) {
            selected[current] = true;
            int next = pathParent[current];
            if (next < 0 || next == current) {
                return;
            }
            current = next;
        }
    }

    private static void installClockwiseMacroCycle(
            int[] successor,
            byte[] kind,
            int x,
            int z
    ) {
        int topLeft = index(x, z);
        int topRight = index(x + 1, z);
        int bottomRight = index(x + 1, z + 1);
        int bottomLeft = index(x, z + 1);
        successor[topLeft] = topRight;
        successor[topRight] = bottomRight;
        successor[bottomRight] = bottomLeft;
        successor[bottomLeft] = topLeft;
        kind[topLeft] = 2;
        kind[topRight] = 2;
        kind[bottomRight] = 2;
        kind[bottomLeft] = 2;
    }

    private static void mergeMacroCycles(
            boolean[] macroNodes,
            int[] componentByCell,
            int componentCount,
            boolean[] walkable,
            float[] heights,
            int[] successor
    ) {
        int[] horizontalEdges = new int[componentCount];
        int[] verticalEdges = new int[componentCount];
        countMacroEdges(
                macroNodes,
                componentByCell,
                walkable,
                heights,
                horizontalEdges,
                verticalEdges);
        boolean[] horizontalFirst = new boolean[componentCount];
        for (int component = 0; component < componentCount; component++) {
            horizontalFirst[component] = horizontalEdges[component]
                    >= verticalEdges[component];
        }

        int[] parent = new int[macroNodes.length];
        Arrays.fill(parent, -1);
        for (int node = 0; node < macroNodes.length; node++) {
            if (macroNodes[node]) {
                parent[node] = node;
            }
        }
        mergeMacroEdges(
                true,
                true,
                macroNodes,
                componentByCell,
                horizontalFirst,
                walkable,
                heights,
                successor,
                parent);
        mergeMacroEdges(
                false,
                true,
                macroNodes,
                componentByCell,
                horizontalFirst,
                walkable,
                heights,
                successor,
                parent);
        mergeMacroEdges(
                true,
                false,
                macroNodes,
                componentByCell,
                horizontalFirst,
                walkable,
                heights,
                successor,
                parent);
        mergeMacroEdges(
                false,
                false,
                macroNodes,
                componentByCell,
                horizontalFirst,
                walkable,
                heights,
                successor,
                parent);
    }

    private static void countMacroEdges(
            boolean[] macroNodes,
            int[] componentByCell,
            boolean[] walkable,
            float[] heights,
            int[] horizontalEdges,
            int[] verticalEdges
    ) {
        for (int parity = 0; parity < 4; parity++) {
            for (int macroZ = 0; macroZ < MACRO_EDGE; macroZ++) {
                for (int macroX = 0; macroX < MACRO_EDGE; macroX++) {
                    int node = macroNode(parity, macroX, macroZ);
                    if (!macroNodes[node]) {
                        continue;
                    }
                    int component = macroComponent(
                            parity, macroX, macroZ, componentByCell);
                    if (macroX + 1 < MACRO_EDGE
                            && canJoinActiveMacroEdge(
                                    macroNodes,
                                    parity,
                                    macroX,
                                    macroZ,
                                    macroX + 1,
                                    macroZ,
                                    walkable,
                                    heights)) {
                        horizontalEdges[component]++;
                    }
                    if (macroZ + 1 < MACRO_EDGE
                            && canJoinActiveMacroEdge(
                                    macroNodes,
                                    parity,
                                    macroX,
                                    macroZ,
                                    macroX,
                                    macroZ + 1,
                                    walkable,
                                    heights)) {
                        verticalEdges[component]++;
                    }
                }
            }
        }
    }

    private static void mergeMacroEdges(
            boolean horizontal,
            boolean primaryPass,
            boolean[] macroNodes,
            int[] componentByCell,
            boolean[] horizontalFirst,
            boolean[] walkable,
            float[] heights,
            int[] successor,
            int[] parent
    ) {
        for (int parity = 0; parity < 4; parity++) {
            for (int macroZ = 0; macroZ < MACRO_EDGE; macroZ++) {
                for (int macroX = 0; macroX < MACRO_EDGE; macroX++) {
                    int nextMacroX = macroX + (horizontal ? 1 : 0);
                    int nextMacroZ = macroZ + (horizontal ? 0 : 1);
                    if (nextMacroX >= MACRO_EDGE || nextMacroZ >= MACRO_EDGE) {
                        continue;
                    }
                    int node = macroNode(parity, macroX, macroZ);
                    if (!macroNodes[node]) {
                        continue;
                    }
                    int component = macroComponent(
                            parity, macroX, macroZ, componentByCell);
                    if ((horizontal == horizontalFirst[component]) != primaryPass
                            || !canJoinActiveMacroEdge(
                                    macroNodes,
                                    parity,
                                    macroX,
                                    macroZ,
                                    nextMacroX,
                                    nextMacroZ,
                                    walkable,
                                    heights)) {
                        continue;
                    }
                    int next = macroNode(parity, nextMacroX, nextMacroZ);
                    if (!unionMacroComponents(parent, node, next)) {
                        continue;
                    }
                    spliceMacroCycles(
                            parity,
                            macroX,
                            macroZ,
                            nextMacroX,
                            nextMacroZ,
                            successor);
                }
            }
        }
    }

    private static boolean canJoinActiveMacroEdge(
            boolean[] macroNodes,
            int parity,
            int firstMacroX,
            int firstMacroZ,
            int secondMacroX,
            int secondMacroZ,
            boolean[] walkable,
            float[] heights
    ) {
        return macroNodes[macroNode(parity, secondMacroX, secondMacroZ)]
                && canJoinMacroBlocks(
                        parity,
                        firstMacroX,
                        firstMacroZ,
                        secondMacroX,
                        secondMacroZ,
                        walkable,
                        heights);
    }

    private static int macroComponent(
            int parity,
            int macroX,
            int macroZ,
            int[] componentByCell
    ) {
        int offsetX = parity & 1;
        int offsetZ = parity >>> 1;
        return componentByCell[index(
                offsetX + macroX * MACRO_CELL_SIZE,
                offsetZ + macroZ * MACRO_CELL_SIZE)];
    }

    private static int macroNode(int parity, int macroX, int macroZ) {
        return parity * MACRO_CELL_COUNT + macroZ * MACRO_EDGE + macroX;
    }

    private static boolean unionMacroComponents(int[] parent, int first, int second) {
        int firstRoot = macroRoot(parent, first);
        int secondRoot = macroRoot(parent, second);
        if (firstRoot == secondRoot) {
            return false;
        }
        parent[secondRoot] = firstRoot;
        return true;
    }

    private static int macroRoot(int[] parent, int node) {
        int root = node;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[node] != node) {
            int next = parent[node];
            parent[node] = root;
            node = next;
        }
        return root;
    }

    // 单格桥无法承载环的双向分支，因此每个连通分量只保留覆盖最大的宏格环。
    private static int keepLargestCirculationPerComponent(
            int[] componentByCell,
            int[] successor,
            byte[] kind,
            int componentCount
    ) {
        int[] cycleByCell = new int[CELL_COUNT];
        Arrays.fill(cycleByCell, -1);
        int[] cycleComponent = new int[CELL_COUNT];
        int[] cycleLength = new int[CELL_COUNT];
        int cycleCount = 0;
        for (int start = 0; start < CELL_COUNT; start++) {
            if (kind[start] != 2 || cycleByCell[start] >= 0) {
                continue;
            }
            int current = start;
            int length = 0;
            while (cycleByCell[current] < 0) {
                cycleByCell[current] = cycleCount;
                length++;
                current = successor[current];
            }
            cycleComponent[cycleCount] = componentByCell[start];
            cycleLength[cycleCount] = length;
            cycleCount++;
        }

        int[] selectedCycle = new int[componentCount];
        Arrays.fill(selectedCycle, -1);
        for (int cycle = 0; cycle < cycleCount; cycle++) {
            int component = cycleComponent[cycle];
            int selected = selectedCycle[component];
            if (selected < 0 || cycleLength[cycle] > cycleLength[selected]) {
                selectedCycle[component] = cycle;
            }
        }

        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (kind[cell] == 2
                    && cycleByCell[cell] != selectedCycle[componentByCell[cell]]) {
                kind[cell] = 0;
                successor[cell] = -1;
            }
        }
        int circulationCount = 0;
        for (int selected : selectedCycle) {
            if (selected >= 0) {
                circulationCount++;
            }
        }
        return circulationCount;
    }

    private static boolean canJoinMacroBlocks(
            int parity,
            int firstMacroX,
            int firstMacroZ,
            int secondMacroX,
            int secondMacroZ,
            boolean[] walkable,
            float[] heights
    ) {
        int offsetX = parity & 1;
        int offsetZ = parity >>> 1;
        if (firstMacroZ == secondMacroZ) {
            int leftMacroX = Math.min(firstMacroX, secondMacroX);
            int leftX = offsetX + leftMacroX * MACRO_CELL_SIZE;
            int z = offsetZ + firstMacroZ * MACRO_CELL_SIZE;
            return canTraverse(walkable, heights, leftX + 1, z, leftX + 2, z)
                    && canTraverse(
                            walkable, heights, leftX + 1, z + 1, leftX + 2, z + 1);
        }
        int upperMacroZ = Math.min(firstMacroZ, secondMacroZ);
        int x = offsetX + firstMacroX * MACRO_CELL_SIZE;
        int upperZ = offsetZ + upperMacroZ * MACRO_CELL_SIZE;
        return canTraverse(walkable, heights, x, upperZ + 1, x, upperZ + 2)
                && canTraverse(
                        walkable, heights, x + 1, upperZ + 1, x + 1, upperZ + 2);
    }

    private static void spliceMacroCycles(
            int parity,
            int firstMacroX,
            int firstMacroZ,
            int secondMacroX,
            int secondMacroZ,
            int[] successor
    ) {
        int offsetX = parity & 1;
        int offsetZ = parity >>> 1;
        if (firstMacroZ == secondMacroZ) {
            int leftMacroX = Math.min(firstMacroX, secondMacroX);
            int leftX = offsetX + leftMacroX * MACRO_CELL_SIZE;
            int z = offsetZ + firstMacroZ * MACRO_CELL_SIZE;
            successor[index(leftX + 1, z)] = index(leftX + 2, z);
            successor[index(leftX + 2, z + 1)] = index(leftX + 1, z + 1);
            return;
        }
        int upperMacroZ = Math.min(firstMacroZ, secondMacroZ);
        int x = offsetX + firstMacroX * MACRO_CELL_SIZE;
        int upperZ = offsetZ + upperMacroZ * MACRO_CELL_SIZE;
        successor[index(x + 1, upperZ + 1)] = index(x + 1, upperZ + 2);
        successor[index(x, upperZ + 2)] = index(x, upperZ + 1);
    }

    private static int installFallbackCycles(
            int[] componentByCell,
            boolean[] componentHasMainStream,
            boolean[] walkable,
            float[] heights,
            float[] clearance,
            int[] successor,
            byte[] kind
    ) {
        int componentCount = componentHasMainStream.length;
        int[] bestCurrent = new int[componentCount];
        int[] bestLength = new int[componentCount];
        Arrays.fill(bestCurrent, -1);
        byte[] state = new byte[CELL_COUNT];
        int[] parent = new int[CELL_COUNT];
        int[] depth = new int[CELL_COUNT];
        int[] nextDirection = new int[CELL_COUNT];
        int[] stack = new int[CELL_COUNT];
        Arrays.fill(parent, -1);

        for (int start = 0; start < CELL_COUNT; start++) {
            int component = componentByCell[start];
            if (component < 0
                    || componentHasMainStream[component]
                    || clearance[start] < MIN_MAIN_STREAM_CLEARANCE
                    || state[start] != 0) {
                continue;
            }
            int stackSize = 1;
            stack[0] = start;
            state[start] = 1;
            while (stackSize > 0) {
                int current = stack[stackSize - 1];
                if (nextDirection[current] >= CARDINAL_X.length) {
                    state[current] = 2;
                    stackSize--;
                    continue;
                }
                int direction = nextDirection[current]++;
                int x = current % SIZE;
                int z = current / SIZE;
                int nextX = x + CARDINAL_X[direction];
                int nextZ = z + CARDINAL_Z[direction];
                if (!canTraverse(walkable, heights, x, z, nextX, nextZ)) {
                    continue;
                }
                int next = index(nextX, nextZ);
                if (componentByCell[next] != component
                        || clearance[next] < MIN_MAIN_STREAM_CLEARANCE
                        || next == parent[current]) {
                    continue;
                }
                if (state[next] == 0) {
                    parent[next] = current;
                    depth[next] = depth[current] + 1;
                    state[next] = 1;
                    stack[stackSize++] = next;
                } else if (state[next] == 1 && depth[next] < depth[current]) {
                    int length = depth[current] - depth[next] + 1;
                    if (length > bestLength[component]) {
                        bestLength[component] = length;
                        bestCurrent[component] = current;
                    }
                }
            }
        }

        int circulationCount = 0;
        for (int component = 0; component < componentCount; component++) {
            int length = bestLength[component];
            if (length < 4) {
                continue;
            }
            int[] cycle = new int[length];
            int cell = bestCurrent[component];
            for (int ordinal = length - 1; ordinal >= 0; ordinal--) {
                cycle[ordinal] = cell;
                if (ordinal > 0) {
                    cell = parent[cell];
                }
            }
            float signedArea = 0.0F;
            for (int ordinal = 0; ordinal < length; ordinal++) {
                int current = cycle[ordinal];
                int next = cycle[(ordinal + 1) % length];
                signedArea += (current % SIZE) * (next / SIZE)
                        - (next % SIZE) * (current / SIZE);
            }
            for (int ordinal = 0; ordinal < length; ordinal++) {
                int current = cycle[ordinal];
                int nextOrdinal = signedArea >= 0.0F
                        ? (ordinal + 1) % length
                        : Math.floorMod(ordinal - 1, length);
                successor[current] = cycle[nextOrdinal];
                kind[current] = 2;
            }
            circulationCount++;
        }
        return circulationCount;
    }

    private static boolean canTraverse(
            boolean[] walkable,
            float[] heights,
            int fromX,
            int fromZ,
            int toX,
            int toZ
    ) {
        if (!inside(toX, toZ) || !walkable[index(toX, toZ)]) {
            return false;
        }
        int deltaX = toX - fromX;
        int deltaZ = toZ - fromZ;
        int from = index(fromX, fromZ);
        int to = index(toX, toZ);
        if (Math.abs(heights[to] - heights[from]) > MAX_STEP_HEIGHT) {
            return false;
        }
        if (deltaX == 0 || deltaZ == 0) {
            return true;
        }
        int acrossX = index(fromX + deltaX, fromZ);
        int acrossZ = index(fromX, fromZ + deltaZ);
        return walkable[acrossX]
                && walkable[acrossZ]
                && Math.abs(heights[acrossX] - heights[from]) <= MAX_STEP_HEIGHT
                && Math.abs(heights[acrossZ] - heights[from]) <= MAX_STEP_HEIGHT;
    }

    private static int index(int localX, int localZ) {
        return localZ * SIZE + localX;
    }

    private static boolean inside(int localX, int localZ) {
        return localX >= 0 && localX < SIZE && localZ >= 0 && localZ < SIZE;
    }

    record DirectedFlow(
            float[] x,
            float[] z,
            byte[] kind,
            int[] successor,
            int mainStreamCellCount,
            int tributaryCellCount,
            int circulationCount
    ) {
    }
}

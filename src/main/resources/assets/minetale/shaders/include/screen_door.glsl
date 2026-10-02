// 共用屏幕像素阈值，原版和 Iris 维护同一套规则
void minetale_screenDoor(float coverage) {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int rank = 0;
    for (int bit = 0; bit < 3; ++bit) {
        int x = (pixel.x >> bit) & 1;
        int y = (pixel.y >> bit) & 1;
        rank = (rank << 2) | ((x ^ y) << 1) | y;
    }
    if (coverage * 64.0 <= float(rank)) discard;
}

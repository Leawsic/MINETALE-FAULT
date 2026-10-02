package cn.jehorstudio.minetale.contentpack.data;

import net.neoforged.neoforge.common.data.LanguageProvider;

public final class ContentPackTranslations {
    public static void addEnUs(LanguageProvider provider) {
        provider.add("minetale.configuration.content_packs", "Content Packs");
        provider.add("screen.minetale.content_pack.title", "MineTale Content Packs");
        provider.add("screen.minetale.content_pack.profile_title", "Content Packs for This World");
        provider.add("screen.minetale.content_pack.open_folder", "Open Install Folder");
        provider.add("screen.minetale.content_pack.manage_world", "Enable and Order");
        provider.add("screen.minetale.content_pack.manage_world.unavailable", "Enter a local world to edit content packs. Multiplayer packs are managed by the administrator.");
        provider.add("screen.minetale.content_pack.summary", "%s packs installed; %s invalid archives rejected.");
        provider.add("screen.minetale.content_pack.authority.local", "This is the local world's active profile.");
        provider.add("screen.minetale.content_pack.authority.offline", "Only the local install library is shown. Enter a local world to edit its active profile; remote servers decide their own profile.");
        provider.add("screen.minetale.content_pack.state.active", "active #%s");
        provider.add("screen.minetale.content_pack.state.inactive", "inactive");
        provider.add("screen.minetale.content_pack.state.rejected", "[rejected] %s: %s");
        provider.add("screen.minetale.content_pack.empty", "The install library is empty. Put Creator-exported .mtpack files in minetale/contentpack.");
        provider.add("screen.minetale.content_pack.reload_success", "Content packs applied");
        provider.add("screen.minetale.content_pack.reload_failed", "Content-pack reload failed; the previous profile was kept");
        provider.add("screen.minetale.content_pack.profile_save_failed", "Content packs reloaded, but the world's Active Profile could not be saved");
    }

    public static void addZhCn(LanguageProvider provider) {
        provider.add("minetale.configuration.content_packs", "内容包");
        provider.add("screen.minetale.content_pack.title", "MineTale 内容包");
        provider.add("screen.minetale.content_pack.profile_title", "当前世界的内容包");
        provider.add("screen.minetale.content_pack.open_folder", "打开安装目录");
        provider.add("screen.minetale.content_pack.manage_world", "启用与排序");
        provider.add("screen.minetale.content_pack.manage_world.unavailable", "进入本地世界后才能修改内容包；多人服务器由管理员管理。");
        provider.add("screen.minetale.content_pack.summary", "已安装 %s 个内容包；拒绝 %s 个无效归档。");
        provider.add("screen.minetale.content_pack.authority.local", "当前列表属于本地世界的活跃包。");
        provider.add("screen.minetale.content_pack.authority.offline", "当前仅展示本机安装库。单个存档启用与否必须进入本地世界后修改；远端世界由服务器决定。");
        provider.add("screen.minetale.content_pack.state.active", "启用 #%s");
        provider.add("screen.minetale.content_pack.state.inactive", "未启用");
        provider.add("screen.minetale.content_pack.state.rejected", "[已拒绝] %s：%s");
        provider.add("screen.minetale.content_pack.empty", "安装库为空。可把 Creator 导出的 .mtpack 放入 minetale/contentpack。");
        provider.add("screen.minetale.content_pack.reload_success", "内容包已应用");
        provider.add("screen.minetale.content_pack.reload_failed", "内容包应用失败，已保留原配置");
        provider.add("screen.minetale.content_pack.profile_save_failed", "内容包已重载，但世界 Active Profile 保存失败");
    }

    private ContentPackTranslations() {}
}

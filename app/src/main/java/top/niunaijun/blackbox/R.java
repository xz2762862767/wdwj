package top.niunaijun.blackbox;

/**
 * 内嵌 BlackBox 脱壳引擎的资源映射。
 *
 * <p>BlackBox 的部分类会读取 {@code R.string.black_box_service_name} 来识别
 * “服务进程”（宿主包名 + :black）。抽取出的 BlackBox 字节码里原本指向 Epic 编译期的
 * 资源 ID，直接使用会错位，因此这里把它映射到「我的文件」自己的字符串资源。
 *
 * <p>字节码里只引用了这一个资源字段，其余 R$* 类已从 jars 中移除。
 */
public final class R {

    private R() {
    }

    public static final class string {
        /** 与 AndroidManifest 中 DaemonService 的 android:process=":black" 保持一致。 */
        public static final int black_box_service_name = com.mtstyle.fm.R.string.black_box_service_name;

        private string() {
        }
    }
}

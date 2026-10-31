package dev.colorwar;

/** Aislado en su propia clase: solo se carga si PlaceholderAPI está instalado. */
final class PapiHook {
    private PapiHook() {}

    static Runnable register(ColorWarPlugin plugin) {
        ColorWarExpansion ex = new ColorWarExpansion(plugin);
        ex.register();
        return ex::unregister;
    }
}

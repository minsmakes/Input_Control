fn main() {
    for f in [
        "native/companion.cpp",
        "native/focus.cpp",
        "native/audio.cpp",
        "native/dashboard.cpp",
        "assets/input-control.ico",
    ] {
        println!("cargo:rerun-if-changed={f}");
    }
    cc::Build::new()
        .cpp(true)
        .flag_if_supported("/std:c++17")
        .flag_if_supported("/utf-8")
        .flag_if_supported("/EHsc")
        .define("UNICODE", None)
        .define("_UNICODE", None)
        .files([
            "native/companion.cpp",
            "native/focus.cpp",
            "native/audio.cpp",
            "native/dashboard.cpp",
        ])
        .compile("companion");
    let mut res = winresource::WindowsResource::new();
    res.set_icon("assets/input-control.ico")
        .set("ProductName", "Input_Control")
        .set("CompanyName", "M. Chung")
        .set("FileDescription", "Input_Control Windows companion")
        .compile()
        .expect("Windows SDK resource compiler");
    for lib in [
        "windowsapp",
        "user32",
        "gdi32",
        "shell32",
        "comctl32",
        "advapi32",
        "ole32",
        "oleaut32",
        "uiautomationcore",
        "dwmapi",
        "uxtheme",
    ] {
        println!("cargo:rustc-link-lib={lib}")
    }
}

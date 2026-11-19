{
  description = "thor-emu: IME pin persistence and gamepad mouse for AYN Thor";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      sdk = (pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ "35.0.0" ];
        includeNDK = false;
        includeEmulator = false;
      }).androidsdk;
      sdkRoot = "${sdk}/libexec/android-sdk";
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ sdk pkgs.gradle pkgs.jdk17 pkgs.zig pkgs.android-tools pkgs.zip ];
        ANDROID_HOME = sdkRoot;
        ANDROID_SDK_ROOT = sdkRoot;
        JAVA_HOME = pkgs.jdk17.home;
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/35.0.0/aapt2";
      };
    };
}

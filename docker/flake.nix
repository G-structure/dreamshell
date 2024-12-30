{
  description = "Nix flake to build a NixOS-based Docker image";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-23.05";

  outputs = { self, nixpkgs }: let
    system = "aarch64-linux";
    pkgs = import nixpkgs {
      inherit system;
      config.allowUnfree = true;
      config.allowUnsupportedSystem = true;
    };
  in {
    dockerImage = pkgs.dockerTools.buildImage {
      name = "dreamshell-nixos";
      tag = "latest";

      copyToRoot = pkgs.buildEnv {
        name = "image-root";
        paths = [
          pkgs.bash
          pkgs.coreutils
          pkgs.docker
          pkgs.cowsay
        ];
        pathsToLink = [ "/bin" ];
      };

      runAsRoot = ''
        #!${pkgs.runtimeShell}
        mkdir -p /data
      '';

      config = {
        Cmd = [ "/bin/bash" "-c" "cowsay hello" ];
        WorkingDir = "/data";
        ExposedPorts = {
          "22/tcp" = {};
          "80/tcp" = {};
        };
      };

      diskSize = 1024;
      buildVMMemorySize = 512;
    };
  };
}

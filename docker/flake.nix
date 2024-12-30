# docker/flake.nix
{
  description = "Nix flake to build a NixOS-based Docker image";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-23.05";

  outputs = { self, nixpkgs }: let
    system = "x86_64-linux";  # or your target system
    pkgs = nixpkgs.legacyPackages.${system};
  in {
    dockerImage = pkgs.dockerTools.buildImage {
      name = "dreamshell-nixos";
      tag = "latest";
      contents = [
        pkgs.bash
        pkgs.coreutils
        pkgs.docker
        # Add other necessary packages here
      ];
      config = {
        Cmd = [ "/bin/bash" ];
        ExposedPorts = {
          "22/tcp" = {};
          "80/tcp" = {};
        };
      };
    };
  };
}

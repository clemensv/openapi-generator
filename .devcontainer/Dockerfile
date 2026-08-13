FROM mcr.microsoft.com/devcontainers/base:debian

ENV NPM_CONFIG_REGISTRY=https://packagefeedproxy.microsoft.io/npm/

RUN printf "registry=%s\n" "${NPM_CONFIG_REGISTRY}" > /root/.npmrc \
    && printf "registry=%s\n" "${NPM_CONFIG_REGISTRY}" > /home/vscode/.npmrc \
    && chown vscode:vscode /home/vscode/.npmrc

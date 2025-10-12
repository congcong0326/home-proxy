#!/bin/bash

# 前端构建和部署脚本 (Linux/Mac版本)
# 用于将React前端打包并部署到Spring Boot后端

set -e  # 遇到错误立即退出

# 参数解析
CLEAN=false
SKIP_BUILD=false
PROFILE="prod"

while [[ $# -gt 0 ]]; do
    case $1 in
        --clean)
            CLEAN=true
            shift
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --profile)
            PROFILE="$2"
            shift 2
            ;;
        *)
            echo "未知参数: $1"
            echo "用法: $0 [--clean] [--skip-build] [--profile prod|dev]"
            exit 1
            ;;
    esac
done

# 获取脚本所在目录的父目录（项目根目录）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
BACKEND_STATIC_DIR="$PROJECT_ROOT/backend/control-manager/src/main/resources/static"

echo "=== 前端构建和部署脚本 ==="
echo "项目根目录: $PROJECT_ROOT"
echo "前端目录: $FRONTEND_DIR"
echo "后端静态资源目录: $BACKEND_STATIC_DIR"

# 检查前端目录是否存在
if [ ! -d "$FRONTEND_DIR" ]; then
    echo "错误: 前端目录不存在: $FRONTEND_DIR"
    exit 1
fi

# 检查package.json是否存在
if [ ! -f "$FRONTEND_DIR/package.json" ]; then
    echo "错误: package.json不存在: $FRONTEND_DIR/package.json"
    exit 1
fi

# 切换到前端目录
cd "$FRONTEND_DIR"

# 清理旧的构建文件
if [ "$CLEAN" = true ]; then
    echo "清理旧的构建文件..."
    [ -d "build" ] && rm -rf build && echo "已删除 build 目录"
    [ -d "node_modules" ] && rm -rf node_modules && echo "已删除 node_modules 目录"
fi

# 安装依赖
if [ ! -d "node_modules" ] || [ "$CLEAN" = true ]; then
    echo "安装前端依赖..."
    npm install
    echo "依赖安装完成"
fi

# 构建前端
if [ "$SKIP_BUILD" != true ]; then
    echo "构建前端应用..."
    if [ "$PROFILE" = "prod" ]; then
        npm run build:prod
    else
        npm run build
    fi
    echo "前端构建完成"
fi

# 检查构建输出
BUILD_DIR="$FRONTEND_DIR/build"
if [ ! -d "$BUILD_DIR" ]; then
    echo "错误: 构建输出目录不存在: $BUILD_DIR"
    exit 1
fi

# 创建后端静态资源目录
mkdir -p "$BACKEND_STATIC_DIR"
echo "确保后端静态资源目录存在: $BACKEND_STATIC_DIR"

# 清理旧的静态文件
echo "清理旧的静态文件..."
rm -rf "$BACKEND_STATIC_DIR"/*
echo "旧静态文件清理完成"

# 复制构建文件到后端
echo "复制构建文件到后端..."
cp -r "$BUILD_DIR"/* "$BACKEND_STATIC_DIR"/
echo "文件复制完成"

# 验证关键文件
if [ -f "$BACKEND_STATIC_DIR/index.html" ]; then
    echo "验证成功: index.html 已复制"
else
    echo "错误: 验证失败: index.html 未找到"
    exit 1
fi

echo "=== 构建和部署完成 ==="
echo "前端应用已成功打包并部署到Spring Boot后端"
echo "静态文件位置: $BACKEND_STATIC_DIR"
echo ""
echo "下一步:"
echo "1. 启动Spring Boot应用"
echo "2. 访问 http://localhost:8080 查看应用"
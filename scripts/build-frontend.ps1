# 前端构建和部署脚本
# 用于将React前端打包并部署到Spring Boot后端

param(
    [switch]$Clean = $false,
    [switch]$SkipBuild = $false,
    [string]$Profile = "prod"
)

# 设置错误处理
$ErrorActionPreference = "Stop"

# 获取脚本所在目录的父目录（项目根目录）
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$FrontendDir = Join-Path $ProjectRoot "frontend"
$BackendStaticDir = Join-Path $ProjectRoot "backend\control-manager\src\main\resources\static"

Write-Host "=== 前端构建和部署脚本 ===" -ForegroundColor Green
Write-Host "项目根目录: $ProjectRoot" -ForegroundColor Yellow
Write-Host "前端目录: $FrontendDir" -ForegroundColor Yellow
Write-Host "后端静态资源目录: $BackendStaticDir" -ForegroundColor Yellow

# 检查前端目录是否存在
if (-not (Test-Path $FrontendDir)) {
    Write-Error "前端目录不存在: $FrontendDir"
    exit 1
}

# 检查package.json是否存在
$PackageJsonPath = Join-Path $FrontendDir "package.json"
if (-not (Test-Path $PackageJsonPath)) {
    Write-Error "package.json不存在: $PackageJsonPath"
    exit 1
}

# 切换到前端目录
Push-Location $FrontendDir

try {
    # 清理旧的构建文件
    if ($Clean) {
        Write-Host "清理旧的构建文件..." -ForegroundColor Cyan
        if (Test-Path "build") {
            Remove-Item -Recurse -Force "build"
            Write-Host "已删除 build 目录" -ForegroundColor Green
        }
        if (Test-Path "node_modules") {
            Remove-Item -Recurse -Force "node_modules"
            Write-Host "已删除 node_modules 目录" -ForegroundColor Green
        }
    }

    # 安装依赖
    if (-not (Test-Path "node_modules") -or $Clean) {
        Write-Host "安装前端依赖..." -ForegroundColor Cyan
        npm install
        if ($LASTEXITCODE -ne 0) {
            throw "npm install 失败"
        }
        Write-Host "依赖安装完成" -ForegroundColor Green
    }

    # 构建前端
    if (-not $SkipBuild) {
        Write-Host "构建前端应用..." -ForegroundColor Cyan
        if ($Profile -eq "prod") {
            npm run build:prod
        } else {
            npm run build
        }
        if ($LASTEXITCODE -ne 0) {
            throw "前端构建失败"
        }
        Write-Host "前端构建完成" -ForegroundColor Green
    }

    # 检查构建输出
    $BuildDir = Join-Path $FrontendDir "build"
    if (-not (Test-Path $BuildDir)) {
        throw "构建输出目录不存在: $BuildDir"
    }

    # 创建后端静态资源目录
    if (-not (Test-Path $BackendStaticDir)) {
        New-Item -ItemType Directory -Path $BackendStaticDir -Force | Out-Null
        Write-Host "创建后端静态资源目录: $BackendStaticDir" -ForegroundColor Green
    }

    # 清理旧的静态文件
    Write-Host "清理旧的静态文件..." -ForegroundColor Cyan
    Get-ChildItem -Path $BackendStaticDir -Recurse | Remove-Item -Force -Recurse
    Write-Host "旧静态文件清理完成" -ForegroundColor Green

    # 复制构建文件到后端
    Write-Host "复制构建文件到后端..." -ForegroundColor Cyan
    Copy-Item -Path "$BuildDir\*" -Destination $BackendStaticDir -Recurse -Force
    Write-Host "文件复制完成" -ForegroundColor Green

    # 验证关键文件
    $IndexHtml = Join-Path $BackendStaticDir "index.html"
    if (Test-Path $IndexHtml) {
        Write-Host "验证成功: index.html 已复制" -ForegroundColor Green
    } else {
        throw "验证失败: index.html 未找到"
    }

    Write-Host "=== 构建和部署完成 ===" -ForegroundColor Green
    Write-Host "前端应用已成功打包并部署到Spring Boot后端" -ForegroundColor Green
    Write-Host "静态文件位置: $BackendStaticDir" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "下一步:" -ForegroundColor Cyan
    Write-Host "1. 启动Spring Boot应用" -ForegroundColor White
    Write-Host "2. 访问 http://localhost:8080 查看应用" -ForegroundColor White

} catch {
    Write-Error "构建过程中发生错误: $_"
    exit 1
} finally {
    # 返回原目录
    Pop-Location
}
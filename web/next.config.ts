import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  transpilePackages: ['antd'],
  compiler: {
    emotion: true,
  },
};

export default nextConfig;

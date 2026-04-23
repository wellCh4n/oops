import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { ThemeProvider } from "next-themes";
import { AppLayout } from "@/components/app-layout";
import { cookies } from "next/headers";
import { Locale, defaultLocale } from "@/lib/i18n";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "OOPS",
  description: "OOPS: Kubernetes Is All You Need.",
};

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const cookieStore = await cookies()
  const sidebarState = cookieStore.get("sidebar_state")
  const defaultSidebarOpen = sidebarState ? sidebarState.value === "true" : true
  const localeCookie = cookieStore.get("locale")?.value
  const initialLocale: Locale =
    localeCookie === "zh-CN" ||
    localeCookie === "zh-TW" ||
    localeCookie === "en-US" ||
    localeCookie === "ja-JP"
      ? localeCookie
      : defaultLocale

  return (
    <html lang={initialLocale} suppressHydrationWarning>
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        <svg className="absolute w-0 h-0" aria-hidden="true">
          <defs>
            <filter id="white-stroke" x="-20%" y="-20%" width="140%" height="140%">
              <feMorphology in="SourceAlpha" result="DILATED" operator="dilate" radius="0.5" />
              <feFlood floodColor="white" result="WHITE" />
              <feComposite in="WHITE" in2="DILATED" operator="in" result="STROKE" />
              <feMerge>
                <feMergeNode in="STROKE" />
                <feMergeNode in="SourceGraphic" />
              </feMerge>
            </filter>
          </defs>
        </svg>
        <ThemeProvider
          attribute="class"
          defaultTheme="system"
          enableSystem
          disableTransitionOnChange
        >
          <AppLayout defaultSidebarOpen={defaultSidebarOpen} initialLocale={initialLocale}>{children}</AppLayout>
        </ThemeProvider>
      </body>
    </html>
  );
}

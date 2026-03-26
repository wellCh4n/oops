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
  const initialLocale: Locale = localeCookie === "en" || localeCookie === "zh" ? localeCookie : defaultLocale

  return (
    <html lang={initialLocale} suppressHydrationWarning>
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
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

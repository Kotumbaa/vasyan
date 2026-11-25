import { HashRouter } from "react-router-dom";
import { ThemeProvider } from "next-themes";
import {
  ToastProvider,
  AppleEmojiProvider,
  RootErrorBoundary,
  GlobalTooltip,
  WindowAnimationContainer,
} from "@/shared/ui";
import { useWindowDrag } from "@/shared/hooks";
import { useAppIconSync, useLanConnectSync } from "@/features/player";
import { I18nProvider } from "@/languages";
import { AuthLock } from "@/features/auth";
import { AppFrame } from "@/features/navigation";
import { Header, Sidebar } from "@/widgets";
import { CoverSwRegistrar } from "@/features/covers";
import { usePresenceSync } from "@/shared/presence";
import { useTouchBarSync } from "@/features/touchbar";
import { useOverlaySync } from "@/features/overlay";
import { useDiscordSync } from "@/features/discord";
import { AppRoutes } from "./routes";
import DeeplinkHandler from "./DeeplinkHandler";
import EnvironmentWarning from "./EnvironmentWarning";
import { ConnectionIssueModal } from "@/features/connectivity";
import { useThemeCustomizationSync } from "@/features/settings";
import "./globals.css";

function AppIconSync() {
  useAppIconSync();
  return null;
}

function PresenceSync() {
  usePresenceSync();
  return null;
}

function ThemeSync() {
  useThemeCustomizationSync();
  return null;
}

function TouchBarSync() {
  useTouchBarSync();
  return null;
}

function OverlaySync() {
  useOverlaySync();
  return null;
}

function DiscordSync() {
  useDiscordSync();
  return null;
}

function LanConnectSync() {
  useLanConnectSync();
  return null;
}

export default function App() {
  useWindowDrag();

  return (
    <HashRouter>
      <CoverSwRegistrar />
      <ThemeProvider
        attribute="data-theme"
        defaultTheme="system"
        enableSystem={true}
        disableTransitionOnChange
      >
        <GlobalTooltip />
        <ThemeSync />
        <AppIconSync />
        <PresenceSync />
        <TouchBarSync />
        <OverlaySync />
        <DiscordSync />
        <LanConnectSync />
        <AppleEmojiProvider>
          <I18nProvider>
            <ToastProvider>
              <RootErrorBoundary>
                <WindowAnimationContainer>
                  <DeeplinkHandler />
                  <EnvironmentWarning />
                  <ConnectionIssueModal />
                  <AuthLock
                    renderShell={(routes) => (
                      <AppFrame
                        renderHeader={(slot) => <Header {...slot} />}
                        renderSidebar={(slot) => <Sidebar {...slot} />}
                      >
                        {routes}
                      </AppFrame>
                    )}
                  >
                    <AppRoutes />
                  </AuthLock>
                </WindowAnimationContainer>
              </RootErrorBoundary>
            </ToastProvider>
          </I18nProvider>
        </AppleEmojiProvider>
      </ThemeProvider>
    </HashRouter>
  );
}

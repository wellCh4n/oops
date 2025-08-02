import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { EnvironmentItem } from '@/types/environment';

interface EnvironmentState {
  currentEnvironment: EnvironmentItem | null;
  setCurrentEnvironment: (environment: EnvironmentItem | null) => void;
}

export const useEnvironmentStore = create<EnvironmentState>()(
  persist(
    (set) => ({
      currentEnvironment: null,
      setCurrentEnvironment: (environment) => set({ currentEnvironment: environment }),
    }),
    {
      name: 'environment-storage',
    }
  )
);
'use client';

import {createContext, useContext} from "react";
import { ApplicationItem } from "@/types/application";

export const ApplicationContext = createContext<ApplicationItem | null>(null);

export const useApplicationContext = () => useContext(ApplicationContext);

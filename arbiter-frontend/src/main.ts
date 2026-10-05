import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { disableIosFocusZoom } from './app/core/util/ios-focus-zoom';

disableIosFocusZoom();

bootstrapApplication(App, appConfig).catch((err) => console.error(err));

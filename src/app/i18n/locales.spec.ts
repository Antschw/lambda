import angularJson from '../../../angular.json';
import { LOCALES } from './locales';

describe('LOCALES', () => {
  it('should match the locales built by angular.json', () => {
    const { sourceLocale, locales } = angularJson.projects['i18n-demo'].i18n;

    expect(LOCALES.map(({ code }) => code)).toEqual([sourceLocale, ...Object.keys(locales)]);
  });
});

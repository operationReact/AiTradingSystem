/** @typedef {'pro-terminal' | 'clear-invest'} Experience */
export const EXPERIENCE = Object.freeze({ PRO: 'pro-terminal', CLEAR: 'clear-invest' });
export const EXPERIENCES = Object.freeze(Object.values(EXPERIENCE));
export const EXPERIENCE_KEY = 'sf-experience';
/** @returns {value is Experience} */
export function isExperience(value) { return EXPERIENCES.includes(value); }
/** @returns {Experience} */
export function normalizeExperience(value) { return isExperience(value) ? value : EXPERIENCE.PRO; }

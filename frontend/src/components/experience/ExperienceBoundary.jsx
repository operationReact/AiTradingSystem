import { useApp } from '../../context/AppContext.jsx';

/** Select presentation only. Controllers, auth and query ownership stay above this boundary. */
export default function ExperienceBoundary({ pro, clear }) {
  const { experience } = useApp();
  return experience === 'clear-invest' ? clear : pro;
}

import { Link } from "react-router";

/** Placeholder screen for the `/` route — replaced by the real home screen in a later ticket. */
function HomePage() {
  return (
    <section>
      <p>Welcome. Parking screens will appear here.</p>
      <p>
        <Link to="/vehicles">Your vehicles</Link>
      </p>
    </section>
  );
}

export default HomePage;

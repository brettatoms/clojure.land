import htmx from "htmx.org";

window.htmx = htmx;

// Format popover field values
const fieldFormatters: Record<string, (v: unknown) => string> = {
  stars: (v) => `${(v as number).toLocaleString()} stars`,
  downloadsPerDay: (v) =>
    `${Math.round(v as number).toLocaleString()} downloads/day`,
  releaseDate: (v) =>
    `Released ${new Date(v as string).toLocaleDateString()}`,
  lastUpdated: (v) =>
    `Last updated ${new Date(v as string).toLocaleDateString()}`,
  popularity: (v) => `${(v as number).toFixed(1)} popularity`,
};

// Project popover functionality
function initPopover() {
  const popover = document.getElementById("project-popover");
  const template = document.getElementById(
    "popover-template",
  ) as HTMLTemplateElement;
  if (!popover || !template) return;

  let currentTarget: HTMLElement | null = null;
  let hoverTimeout: number | null = null;

  document.addEventListener(
    "mouseenter",
    (e) => {
      const target = (e.target as HTMLElement).closest(
        "[data-popover]",
      ) as HTMLElement;
      if (!target) return;

      // Clear any existing timeout
      if (hoverTimeout) {
        clearTimeout(hoverTimeout);
        hoverTimeout = null;
      }

      currentTarget = target;

      // Delay showing popover by 500ms
      hoverTimeout = window.setTimeout(() => {
        const data = JSON.parse(target.dataset.popover || "{}");
        const content = template.content.cloneNode(true) as DocumentFragment;

        // Fill in values and hide rows with no data
        let hasContent = false;
        content.querySelectorAll("[data-field]").forEach((row) => {
          const field = (row as HTMLElement).dataset.field!;
          const value = data[field];
          if (value != null && fieldFormatters[field]) {
            row.querySelector("[data-value]")!.textContent =
              fieldFormatters[field](value);
            hasContent = true;
          } else {
            row.remove();
          }
        });

        if (hasContent) {
          popover.replaceChildren(content);
          popover.classList.add("visible");
        }
      }, 500);
    },
    true,
  );

  document.addEventListener(
    "mouseleave",
    (e) => {
      const target = (e.target as HTMLElement).closest("[data-popover]");
      if (target && target === currentTarget) {
        // Clear pending timeout if mouse leaves before delay
        if (hoverTimeout) {
          clearTimeout(hoverTimeout);
          hoverTimeout = null;
        }
        popover.classList.remove("visible");
        currentTarget = null;
      }
    },
    true,
  );

  document.addEventListener("mousemove", (e) => {
    if (!currentTarget) return;

    // Position popover near cursor with offset
    const offsetX = 12;
    const offsetY = 12;

    // Keep popover within viewport
    const popoverRect = popover.getBoundingClientRect();
    let x = e.clientX + offsetX;
    let y = e.clientY + offsetY;

    if (x + popoverRect.width > window.innerWidth) {
      x = e.clientX - popoverRect.width - offsetX;
    }
    if (y + popoverRect.height > window.innerHeight) {
      y = e.clientY - popoverRect.height - offsetY;
    }

    popover.style.left = `${x}px`;
    popover.style.top = `${y}px`;
  });
}

// Initialize on load and after htmx swaps
document.addEventListener("DOMContentLoaded", initPopover);
document.addEventListener("htmx:afterSettle", initPopover);

function appendParameter(
  parameters: { [k: string]: string | Array<string> },
  key: string,
  value: string,
) {
  const values = parameters[key];
  if (typeof values === "string") {
    parameters[key] = [values, value];
  } else if (Array.isArray(values)) {
    values.push(value);
  } else {
    parameters[key] = value;
  }
}

// Hang these functions on window to make sure they're included in the bundle.
window.appendPlatformParameter = (event, platform: string) => {
  const el = document.querySelector(`input[value="${platform}"]`);
  if (el) {
    // cancel the request if the value already exists
    event.preventDefault();
    return;
  }

  appendParameter(event.detail.parameters, "platforms", platform);
};

window.appendTagParameter = (event, tag: string) => {
  console.log(event);
  const el = document.querySelector(`input[value="${tag}"]`);
  if (el) {
    // cancel the request if the value already exists
    event.preventDefault();
    return;
  }

  appendParameter(event.detail.parameters, "tags", tag);
};

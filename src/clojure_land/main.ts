import htmx from "htmx.org";

window.htmx = htmx;

// Format popover field values
const fieldFormatters: Record<string, (v: unknown) => string> = {
  name: (v) => v as string,
  stars: (v) => `${(v as number).toLocaleString()} stars`,
  downloadsPerDay: (v) =>
    `${Math.round(v as number).toLocaleString()} downloads/day`,
  releaseDate: (v) =>
    `Released ${new Date(v as string).toLocaleDateString()}`,
  lastUpdated: (v) =>
    `Last updated ${new Date(v as string).toLocaleDateString()}`,
  popularity: (v) => `${(v as number).toFixed(1)} popularity`,
};

const isTouch = window.matchMedia("(pointer: coarse)").matches;

// Build popover content from template and data, returns true if content was added
function fillPopover(
  popover: HTMLElement,
  template: HTMLTemplateElement,
  data: Record<string, unknown>,
) {
  const content = template.content.cloneNode(true) as DocumentFragment;
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
    return true;
  }
  return false;
}

// Desktop: hover-based popover
function initDesktopPopover() {
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
        "li[data-popover]",
      ) as HTMLElement;
      if (!target) return;

      if (hoverTimeout) {
        clearTimeout(hoverTimeout);
        hoverTimeout = null;
      }

      currentTarget = target;

      hoverTimeout = window.setTimeout(() => {
        const data = JSON.parse(target.dataset.popover || "{}");
        if (fillPopover(popover, template, data)) {
          popover.classList.add("visible");
        }
      }, 500);
    },
    true,
  );

  document.addEventListener(
    "mouseleave",
    (e) => {
      const target = (e.target as HTMLElement).closest("li[data-popover]");
      if (target && target === currentTarget) {
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

    const offsetX = 12;
    const offsetY = 12;
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

// Touch: tap info icon to show popover, icon becomes ✕ to close
function initTouchPopover() {
  const popover = document.getElementById("project-popover");
  const template = document.getElementById(
    "popover-template",
  ) as HTMLTemplateElement;
  if (!popover || !template) return;

  let activeTrigger: HTMLElement | null = null;
  let activeTriggerOriginalHTML: string = "";

  const closeX =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="size-6"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg>';

  function deactivateTrigger() {
    if (activeTrigger) {
      activeTrigger.innerHTML = activeTriggerOriginalHTML;
      activeTrigger = null;
      activeTriggerOriginalHTML = "";
    }
    popover!.classList.remove("visible");
  }

  document.addEventListener("click", (e) => {
    const trigger = (e.target as HTMLElement).closest(
      ".popover-trigger",
    ) as HTMLElement;

    if (trigger) {
      e.preventDefault();
      e.stopPropagation();

      // Tapping the active trigger closes the popover
      if (trigger === activeTrigger) {
        deactivateTrigger();
        return;
      }

      // Close any previously active trigger
      deactivateTrigger();

      const data = JSON.parse(trigger.dataset.popover || "{}");
      if (!fillPopover(popover, template, data)) return;

      // Swap icon to ✕
      activeTriggerOriginalHTML = trigger.innerHTML;
      activeTrigger = trigger;
      trigger.innerHTML = closeX;

      // Position near the trigger icon
      const rect = trigger.getBoundingClientRect();
      let x = rect.left;
      let y = rect.bottom + 8;

      // Show briefly to measure, then adjust
      popover.classList.add("visible");
      const popoverRect = popover.getBoundingClientRect();

      if (x + popoverRect.width > window.innerWidth) {
        x = window.innerWidth - popoverRect.width - 8;
      }
      if (y + popoverRect.height > window.innerHeight) {
        y = rect.top - popoverRect.height - 8;
      }

      popover.style.left = `${x}px`;
      popover.style.top = `${y}px`;
    } else if (!popover.contains(e.target as Node)) {
      deactivateTrigger();
    }
  });
}

// Project popover: initialize once on DOMContentLoaded
// (listeners are on document so they survive htmx swaps)
document.addEventListener("DOMContentLoaded", () => {
  if (isTouch) {
    initTouchPopover();
  } else {
    initDesktopPopover();
  }
});

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

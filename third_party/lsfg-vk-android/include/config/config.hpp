#pragma once

#include <vulkan/vulkan_core.h>

#include <filesystem>
#include <chrono>
#include <cstddef>
#include <string>

namespace Config {

    /// lsfg-vk configuration
    struct Configuration {
        /// Whether lsfg-vk should be loaded in the first place.
        bool enable{false};
        /// Path to Lossless.dll.
        std::string dll;

        /// The frame generation muliplier
        size_t multiplier{2};
        /// The internal flow scale factor
        float flowScale{1.0F};
        /// Whether performance mode is enabled
        bool performance{false};
        /// Android: snapshot on emu thread, finish LSFG on a worker (1 in flight).
        bool asyncPresent{true};
        /// Microseconds to space consecutive presents by. Needed under MAILBOX,
        /// where a burst of gen+real presents collapses to one image. Under FIFO
        /// the presentation engine already paces to vblank, so this is dead time
        /// inside the async worker's budget — set 0 there.
        unsigned presentGapUs{8333};
        /// Whether HDR is enabled
        bool hdr{false};

        /// Experimental flag for overriding the synchronization method.
        VkPresentModeKHR e_present;

        /// Path to the configuration file.
        std::filesystem::path config_file;
        /// File timestamp of the configuration file
        std::chrono::time_point<std::chrono::file_clock> timestamp;
    };

    /// Active configuration. Must be set in main.cpp.
    extern Configuration activeConf;

    ///
    /// Read the configuration file while preserving the previous configuration
    /// in case of an error.
    ///
    /// @param file The path to the configuration file.
    ///
    /// @throws std::runtime_error if an error occurs while loading the configuration file.
    ///
    void updateConfig(const std::string& file);

    ///
    /// Get the configuration for a game.
    ///
    /// @param name The name of the executable to fetch.
    /// @return The configuration for the game or global configuration.
    ///
    /// @throws std::runtime_error if the configuration is invalid.
    ///
    Configuration getConfig(const std::pair<std::string, std::string>& name);

}
